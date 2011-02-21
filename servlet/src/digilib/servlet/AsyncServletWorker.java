/**
 * 
 */
package digilib.servlet;

import java.io.IOException;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

import digilib.image.DocuImage;
import digilib.image.ImageJobDescription;
import digilib.image.ImageOpException;
import digilib.image.ImageWorker;
import digilib.servlet.Scaler.ErrMsg;
import digilib.servlet.Scaler.Error;

/**
 * @author casties
 * 
 */
public class AsyncServletWorker implements Runnable {

    /** the AsyncServlet context */
    private AsyncContext asyncContext;

    /** the ImageWorker we use */
    private ImageWorker imageWorker;

    protected static Logger logger = Logger.getLogger(AsyncServletWorker.class);
    private long startTime;
    ErrMsg errMsgType = ErrMsg.IMAGE;

    /**
     * @param dlConfig
     * @param jobinfo
     */
    public AsyncServletWorker(DigilibConfiguration dlConfig,
            ImageJobDescription jobinfo, AsyncContext asyncContext,
            ErrMsg errMsgType, long startTime) {
        // set up image worker
        imageWorker = new ImageWorker(dlConfig, jobinfo);
        // save AsyncContext
        this.asyncContext = asyncContext;
        this.startTime = startTime;
        this.errMsgType = errMsgType;
    }

    /**
     * runs the ImageWorker and writes the image to the ServletResponse.
     */
    public void run() {
        // get fresh response
        HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
        try {
            // render the image
            DocuImage img = imageWorker.call();
            // send image
            ServletOps.sendImage(img, null, response, logger);
            logger.debug("Job done in: "
                    + (System.currentTimeMillis() - startTime) + "ms");
        } catch (ImageOpException e) {
            logger.error(e.getClass() + ": " + e.getMessage());
            Scaler.digilibError(errMsgType, Error.IMAGE, null, response);
        } catch (IOException e) {
            logger.error(e.getClass() + ": " + e.getMessage());
            Scaler.digilibError(errMsgType, Error.FILE, null, response);
        } catch (ServletException e) {
            logger.error("Servlet error: ", e);
        } catch (Exception e) {
            logger.error("Other error: ", e);
        } finally {
            // submit response
            logger.debug("context complete.");
            asyncContext.complete();
        }

    }

}
