/*  FileOpException -- Exception class for file operations

 Digital Image Library servlet components

 Copyright (C) 2001, 2002 Robert Casties (robcast@mail.berlios.de)

 This program is free software; you can redistribute  it and/or modify it
 under  the terms of  the GNU General  Public License as published by the
 Free Software Foundation;  either version 2 of the  License, or (at your
 option) any later version.
 
 Please read license.txt for the full details. A copy of the GPL
 may be found at http://www.gnu.org/copyleft/lgpl.html

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

 */

package digilib.io;

import java.io.IOException;

public class FileOpException extends IOException {

	private static final long serialVersionUID = 7299056561734277644L;

	public FileOpException() {
	}

	public FileOpException(String s) {
		super(s);
	}

    public FileOpException(String message, Throwable cause) {
        /* only Java6, sigh.
        super(message, cause);
        */
        super(message+" caused by "+cause.toString());
    }
}