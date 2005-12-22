/*
 * Raster -- Servlet for displaying rasterized SVG graphics
 * 
 * Digital Image Library servlet components
 * 
 * Copyright (C) 2003 Robert Casties (robcast@mail.berlios.de)
 * 
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 * 
 * Please read license.txt for the full details. A copy of the GPL may be found
 * at http://www.gnu.org/copyleft/lgpl.html You should have received a copy of
 * the GNU General Public License along with this program; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307 USA
 * 
 * Created on 25.11.2003 by casties
 */

package digilib.servlet;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

import digilib.auth.AuthOpException;
import digilib.auth.AuthOps;
import digilib.image.ImageOpException;
import digilib.image.ImageOps;
import digilib.image.ImageSize;
import digilib.io.DocuDirCache;
import digilib.io.DocuDirectory;
import digilib.io.DocuDirent;
import digilib.io.FileOpException;
import digilib.io.FileOps;
import digilib.io.ImageFile;
import digilib.io.ImageFileset;

/**
 * Servlet for displaying SVG graphics
 * 
 * @author casties
 *  
 */
public class Printer extends HttpServlet {


	private static final long serialVersionUID = -1675239084002546036L;

	/** Servlet version */
	public static String servletVersion = "0.1a1";
	/** DigilibConfiguration instance */
	DigilibConfiguration dlConfig = null;
	/** general logger */
	Logger logger = Logger.getLogger("digilib.printer");
	/** AuthOps instance */
	AuthOps authOp;
	/** DocuDirCache instance */
	DocuDirCache dirCache;
	/** digilib Scaler instance */
	Scaler dlScaler;

	/** use authentication */
	boolean useAuthentication = false;
	
	boolean sendFileAllowed = true;
	boolean wholeRotArea = false;

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
	 */
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		System.out.println(
			"***** Digital Image Library PDF Printer Servlet (version "
				+ servletVersion
				+ ") *****");

		// get our ServletContext
		ServletContext context = config.getServletContext();
		// see if there is a Configuration instance
		dlConfig =
			(DigilibConfiguration) context.getAttribute(
				"digilib.servlet.configuration");
		if (dlConfig == null) {
			// no config
			throw new ServletException("ERROR: No Configuration!");
		}
		// say hello in the log file
		logger.info(
			"***** Digital Image Library PDF Printer Servlet (version "
				+ servletVersion
				+ ") *****");

		// set our AuthOps
		useAuthentication = dlConfig.getAsBoolean("use-authorization");
		authOp = (AuthOps) dlConfig.getValue("servlet.auth.op");
		// DocuDirCache instance
		dirCache = (DocuDirCache) dlConfig.getValue("servlet.dir.cache");
		// Scaler instance
		
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest,
	 *      javax.servlet.http.HttpServletResponse)
	 */
	protected void doGet(
		HttpServletRequest request,
		HttpServletResponse response)
		throws ServletException, IOException {
		// create new request with defaults
		DigilibRequest dlReq = new DigilibRequest();
		// set with request parameters
		dlReq.setWithRequest(request);
		// add DigilibRequest to ServletRequest
		request.setAttribute("digilib.servlet.request", dlReq);
		// do the processing
		processRequest(request, response, dlReq);
	}

	/*
	 */
	protected void doPost(
		HttpServletRequest request,
		HttpServletResponse response)
		throws ServletException, IOException {
		// create new request with defaults
		DigilibRequest dlReq = new DigilibRequest();
		// set with request parameters
		dlReq.setWithRequest(request);
		// add DigilibRequest to ServletRequest
		request.setAttribute("digilib.servlet.request", dlReq);
		// do the processing
		processRequest(request, response, dlReq);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.http.HttpServlet#getLastModified(javax.servlet.http.HttpServletRequest)
	 */
	protected long getLastModified(HttpServletRequest request) {
		logger.debug("GetLastModified from " + request.getRemoteAddr()
				+ " for " + request.getQueryString());
		long mtime = -1;
		// create new request with defaults
		DigilibRequest dlReq = new DigilibRequest();
		// set with request parameters
		dlReq.setWithRequest(request);
		
		// find the file(set)
		DocuDirent f = findFile(dlReq);
		if (f != null) {
			DocuDirectory dd = (DocuDirectory) f.getParent();
			mtime = dd.getDirMTime() / 1000 * 1000;
		}
		return mtime;
	}

	
	
	
	protected void processRequest(
		HttpServletRequest request,
		HttpServletResponse response, DigilibRequest dlRequest)
		throws ServletException, IOException {

		logger.debug("request: "+request.getQueryString());
		// time for benchmarking
		long startTime = System.currentTimeMillis();

		/* preset request parameters */

		// scale the image file to fit window size i.e. respect dw,dh
		boolean scaleToFit = true;
		// scale the image by a fixed factor only
		boolean absoluteScale = false;
		// use low resolution images only
		boolean loresOnly = false;
		// use hires images only
		boolean hiresOnly = false;
		// send the image always as a specific type (e.g. JPEG or PNG)
		int forceType = ImageOps.TYPE_AUTO;
		// interpolation to use for scaling
		int scaleQual = 2;
		// send html error message (or image file)
		boolean errorMsgHtml = false;
		// original (hires) image resolution
		float origResX = 0;
		float origResY = 0;

		/* request parameters */

		// destination image width
		int paramDW = dlRequest.getAsInt("dw");
		// destination image height
		int paramDH = dlRequest.getAsInt("dh");
		// relative area x_offset (0..1)
		float paramWX = dlRequest.getAsFloat("wx");
		// relative area y_offset
		float paramWY = dlRequest.getAsFloat("wy");
		// relative area width (0..1)
		float paramWW = dlRequest.getAsFloat("ww");
		// relative area height
		float paramWH = dlRequest.getAsFloat("wh");
		// scale factor (additional to dw/width, dh/height)
		float paramWS = dlRequest.getAsFloat("ws");
		// rotation angle
		float paramROT = dlRequest.getAsFloat("rot");
		// contrast enhancement
		float paramCONT = dlRequest.getAsFloat("cont");
		// brightness enhancement
		float paramBRGT = dlRequest.getAsFloat("brgt");
		// color modification
		float[] paramRGBM = null;
		Parameter p = dlRequest.get("rgbm");
		if (p.hasValue() && (!p.getAsString().equals("0/0/0"))) {
			paramRGBM = p.parseAsFloatArray("/");
		}
		float[] paramRGBA = null;
		p = dlRequest.get("rgba");
		if (p.hasValue() && (!p.getAsString().equals("0/0/0"))) {
			paramRGBA = p.parseAsFloatArray("/");
		}
		// destination resolution (DPI)
		float paramDDPIX = dlRequest.getAsFloat("ddpix");
		float paramDDPIY = dlRequest.getAsFloat("ddpiy");
		if ((paramDDPIX == 0) || (paramDDPIY == 0)) {
			// if X or Y resolution isn't set, use DDPI
			paramDDPIX = dlRequest.getAsFloat("ddpi");
			paramDDPIY = paramDDPIX;
		}

		/*
		 * operation mode: "fit": always fit to page, "clip": send original
		 * resolution cropped, "file": send whole file (if allowed)
		 */
		if (dlRequest.hasOption("mo", "clip")) {
			scaleToFit = false;
			absoluteScale = false;
			hiresOnly = true;
		} else if (dlRequest.hasOption("mo", "fit")) {
			scaleToFit = true;
			absoluteScale = false;
			hiresOnly = false;
		} else if (dlRequest.hasOption("mo", "osize")) {
			scaleToFit = false;
			absoluteScale = true;
			hiresOnly = true;
		}
		// operation mode: "lores": try to use scaled image, "hires": use
		// unscaled image
		// "autores": try best fitting resolution
		if (dlRequest.hasOption("mo", "lores")) {
			loresOnly = true;
			hiresOnly = false;
		} else if (dlRequest.hasOption("mo", "hires")) {
			loresOnly = false;
			hiresOnly = true;
		} else if (dlRequest.hasOption("mo", "autores")) {
			loresOnly = false;
			hiresOnly = false;
		}
		// operation mode: "errtxt": error message in html, "errimg": error
		// image
		if (dlRequest.hasOption("mo", "errtxt")) {
			errorMsgHtml = true;
		} else if (dlRequest.hasOption("mo", "errimg")) {
			errorMsgHtml = false;
		}
		// operation mode: "q0" - "q2": interpolation quality
		if (dlRequest.hasOption("mo", "q0")) {
			scaleQual = 0;
		} else if (dlRequest.hasOption("mo", "q1")) {
			scaleQual = 1;
		} else if (dlRequest.hasOption("mo", "q2")) {
			scaleQual = 2;
		}
		// operation mode: "jpg": always use JPEG
		if (dlRequest.hasOption("mo", "jpg")) {
			forceType = ImageOps.TYPE_JPEG;
		}
		// operation mode: "png": always use PNG
		if (dlRequest.hasOption("mo", "png")) {
			forceType = ImageOps.TYPE_PNG;
		}

		// check with the maximum allowed size (if set)
		int maxImgSize = dlConfig.getAsInt("max-image-size");
		if (maxImgSize > 0) {
			paramDW = (paramDW * paramWS > maxImgSize) ? (int) (maxImgSize / paramWS)
					: paramDW;
			paramDH = (paramDH * paramWS > maxImgSize) ? (int) (maxImgSize / paramWS)
					: paramDH;
		}

		// "big" try for all file/image actions
		try {

			// ImageFileset of the image to load
			ImageFileset fileset = null;

			/* find the file to load/send */

			// get PathInfo
			String loadPathName = dlRequest.getFilePath();

			/* check permissions */
			if (useAuthentication) {
				// get a list of required roles (empty if no restrictions)
				List rolesRequired = authOp.rolesForPath(loadPathName, request);
				if (rolesRequired != null) {
					logger.debug("Role required: " + rolesRequired);
					logger.debug("User: " + request.getRemoteUser());
					// is the current request/user authorized?
					if (!authOp.isRoleAuthorized(rolesRequired, request)) {
						// send deny answer and abort
						throw new AuthOpException();
					}
				}
			}

			// find the file
			fileset = (ImageFileset) findFile(dlRequest);
			if (fileset == null) {
				throw new FileOpException("File " + loadPathName + "("
						+ dlRequest.getAsInt("pn") + ") not found.");
			}

			/* calculate expected source image size */
			ImageSize expectedSourceSize = new ImageSize();
			if (scaleToFit) {
				float scale = (1 / Math.min(paramWW, paramWH)) * paramWS;
				expectedSourceSize.setSize((int) (paramDW * scale),
						(int) (paramDH * scale));
			} else {
				expectedSourceSize.setSize((int) (paramDW * paramWS),
						(int) (paramDH * paramWS));
			}

			ImageFile fileToLoad;
			/* select a resolution */
			if (hiresOnly) {
				// get first element (= highest resolution)
				fileToLoad = fileset.getBiggest();
			} else if (loresOnly) {
				// enforced lores uses next smaller resolution
				fileToLoad = fileset.getNextSmaller(expectedSourceSize);
				if (fileToLoad == null) {
					// this is the smallest we have
					fileToLoad = fileset.getSmallest();
				}
			} else {
				// autores: use next higher resolution
				fileToLoad = fileset.getNextBigger(expectedSourceSize);
				if (fileToLoad == null) {
					// this is the highest we have
					fileToLoad = fileset.getBiggest();
				}
			}
			logger.info("Planning to load: " + fileToLoad.getFile());

			/*
			 * send the image if its mo=(raw)file
			 */
			if (dlRequest.hasOption("mo", "file")
					|| dlRequest.hasOption("mo", "rawfile")) {
				if (sendFileAllowed) {
					String mt = null;
					if (dlRequest.hasOption("mo", "rawfile")) {
						mt = "application/octet-stream";
					}
					logger.debug("Sending RAW File as is.");
					ServletOps.sendFile(fileToLoad.getFile(), mt, response);
					logger.info("Done in "
							+ (System.currentTimeMillis() - startTime) + "ms");
					return;
				}
			}

			// check the source image
			if (!fileToLoad.isChecked()) {
				ImageOps.checkFile(fileToLoad);
			}
			// get the source image type
			String mimeType = fileToLoad.getMimetype();
			// get the source image size
			ImageSize imgSize = fileToLoad.getSize();

			// decide if the image can be sent as is
			boolean mimetypeSendable = mimeType.equals("image/jpeg")
					|| mimeType.equals("image/png")
					|| mimeType.equals("image/gif");
			boolean imagoOptions = dlRequest.hasOption("mo", "hmir")
					|| dlRequest.hasOption("mo", "vmir") || (paramROT != 0)
					|| (paramRGBM != null) || (paramRGBA != null)
					|| (paramCONT != 0) || (paramBRGT != 0);
			boolean imageSendable = mimetypeSendable && !imagoOptions;

			/*
			 * if not autoRes and image smaller than requested size then send as
			 * is. if autoRes and image has requested size then send as is. if
			 * not autoScale and not scaleToFit nor cropToFit then send as is
			 * (mo=file)
			 */
			if (imageSendable
					&& ((loresOnly && fileToLoad.getSize().isSmallerThan(
							expectedSourceSize)) || (!(loresOnly || hiresOnly) && fileToLoad
							.getSize().fitsIn(expectedSourceSize)))) {

				logger.debug("Sending File as is.");

				ServletOps.sendFile(fileToLoad.getFile(), null, response);

				logger.info("Done in "
						+ (System.currentTimeMillis() - startTime) + "ms");
				return;
			}

			
			/*
			 * stop here if we're overloaded...
			 * 
			 * 503 Service Unavailable 
			 * The server is currently unable to
			 * handle the request due to a temporary overloading or maintenance
			 * of the server. The implication is that this is a temporary
			 * condition which will be alleviated after some delay. If known,
			 * the length of the delay MAY be indicated in a Retry-After header.
			 * If no Retry-After is given, the client SHOULD handle the response
			 * as it would for a 500 response. Note: The existence of the 503
			 * status code does not imply that a server must use it when
			 * becoming overloaded. Some servers may wish to simply refuse the
			 * connection.
			 * (RFC2616 HTTP1.1)
			 */
			if (! DigilibWorker.canRun()) {
				logger.error("Servlet overloaded!");
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			
			// set missing dw or dh from aspect ratio
			float imgAspect = fileToLoad.getAspect();
			if (paramDW == 0) {
				paramDW = (int) Math.round(paramDH * imgAspect);
			} else if (paramDH == 0) {
				paramDH = (int) Math.round(paramDW / imgAspect);
			}

			/*
			 * prepare resolution for original size
			 */
			if (absoluteScale) {
				// get original resolution from metadata
				fileset.checkMeta();
				origResX = fileset.getResX();
				origResY = fileset.getResY();
				if ((origResX == 0) || (origResY == 0)) {
					throw new ImageOpException("Missing image DPI information!");
				}

				if ((paramDDPIX == 0) || (paramDDPIY == 0)) {
					throw new ImageOpException(
							"Missing display DPI information!");
				}
			}

			/* crop and scale the image */

			logger.debug("IMG: " + imgSize.getWidth() + "x"
					+ imgSize.getHeight());
			logger.debug("time " + (System.currentTimeMillis() - startTime)
					+ "ms");

			// coordinates and scaling
			float areaWidth;
			float areaHeight;
			float scaleX;
			float scaleY;
			float scaleXY;

			// coordinates using Java2D
			// image size in pixels
			Rectangle2D imgBounds = new Rectangle2D.Float(0, 0, imgSize
					.getWidth(), imgSize.getHeight());
			// user window area in [0,1] coordinates
			Rectangle2D relUserArea = new Rectangle2D.Float(paramWX, paramWY,
					paramWW, paramWH);
			// transform from relative [0,1] to image coordinates.
			AffineTransform imgTrafo = AffineTransform.getScaleInstance(imgSize
					.getWidth(), imgSize.getHeight());
			// transform user coordinate area to image coordinate area
			Rectangle2D userImgArea = imgTrafo.createTransformedShape(
					relUserArea).getBounds2D();

			// calculate scaling factors based on inner user area
			if (scaleToFit) {
				areaWidth = (float) userImgArea.getWidth();
				areaHeight = (float) userImgArea.getHeight();
				scaleX = paramDW / areaWidth * paramWS;
				scaleY = paramDH / areaHeight * paramWS;
				scaleXY = (scaleX > scaleY) ? scaleY : scaleX;
			} else if (absoluteScale) {
				// absolute scale
				scaleX = paramDDPIX / origResX;
				scaleY = paramDDPIY / origResY;
				// currently only same scale :-(
				scaleXY = scaleX;
				areaWidth = paramDW / scaleXY * paramWS;
				areaHeight = paramDH / scaleXY * paramWS;
				// reset user area size
				userImgArea.setRect(userImgArea.getX(), userImgArea.getY(),
						areaWidth, areaHeight);
			} else {
				// crop to fit
				areaWidth = paramDW * paramWS;
				areaHeight = paramDH * paramWS;
				// reset user area size
				userImgArea.setRect(userImgArea.getX(), userImgArea.getY(),
						areaWidth, areaHeight);
				scaleX = 1f;
				scaleY = 1f;
				scaleXY = 1f;
			}

			// enlarge image area for rotations to cover additional pixels
			Rectangle2D outerUserImgArea = userImgArea;
			Rectangle2D innerUserImgArea = userImgArea;
			if (wholeRotArea) {
				if (paramROT != 0) {
					try {
						// rotate user area coordinates around center of user
						// area
						AffineTransform rotTrafo = AffineTransform
								.getRotateInstance(Math.toRadians(paramROT),
										userImgArea.getCenterX(), userImgArea
												.getCenterY());
						// get bounds from rotated end position
						innerUserImgArea = rotTrafo.createTransformedShape(
								userImgArea).getBounds2D();
						// get bounds from back-rotated bounds
						outerUserImgArea = rotTrafo.createInverse()
								.createTransformedShape(innerUserImgArea)
								.getBounds2D();
					} catch (NoninvertibleTransformException e1) {
						// this shouldn't happen anyway
						logger.error(e1);
					}
				}
			}

			logger.debug("Scale " + scaleXY + "(" + scaleX + "," + scaleY
					+ ") on " + outerUserImgArea);

			// clip area at the image border
			outerUserImgArea = outerUserImgArea.createIntersection(imgBounds);

			// check image parameters sanity
			if ((outerUserImgArea.getWidth() < 1)
					|| (outerUserImgArea.getHeight() < 1)
					|| (scaleXY * outerUserImgArea.getWidth() < 2)
					|| (scaleXY * outerUserImgArea.getHeight() < 2)) {
				logger.error("ERROR: invalid scale parameter set!");
				throw new ImageOpException("Invalid scale parameter set!");
			}

			/*
			 * submit the image worker job
			 */

			mimeType = "application/pdf";
			int minSubsample = 2;
			boolean wholeRotArea = false;
			
			DigilibWorker job = new DigilibImageWorker(dlConfig, response,
					mimeType, scaleQual, dlRequest, paramROT, paramCONT,
					paramBRGT, paramRGBM, paramRGBA, fileToLoad, scaleXY,
					outerUserImgArea, innerUserImgArea, minSubsample,
					wholeRotArea, forceType);

			job.run();
			if (job.hasError()) {
				throw new ImageOpException(job.getError().toString());
			}

			logger.debug("servlet done in "
					+ (System.currentTimeMillis() - startTime));

			/* error handling */

			/*
			 * error handling
			 */
			
		} catch (FileOpException e) {
			logger.error("ERROR: File IO Error: ", e);
			try {
				ServletOps.htmlMessage("ERROR: File IO Error: " + e, response);
			} catch (Exception ex) {
			} // so we don't get a loop
		} catch (ImageOpException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (AuthOpException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	/**
	 * Returns the DocuDirent corresponding to the DigilibRequest.
	 * 
	 * @param dlRequest
	 * @return
	 */
	public DocuDirent findFile(DigilibRequest dlRequest) {
		// find the file(set)
		DocuDirent f = dirCache.getFile(dlRequest.getFilePath(), dlRequest
				.getAsInt("pn"), FileOps.CLASS_IMAGE);
		return f;
	}


}