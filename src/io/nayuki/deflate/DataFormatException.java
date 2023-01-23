/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.IOException;


public final class DataFormatException extends IOException {
	
	public DataFormatException(String msg) {
		super(msg);
	}
	
	
	public DataFormatException(Throwable cause) {
		super(cause);
	}
	
}
