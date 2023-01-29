/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;


public interface Strategy {
	
	public Decision decide(byte[] b, int off, int historyLen, int dataLen);
	
}
