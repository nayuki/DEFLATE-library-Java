/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;


public interface BitOutputStream {
	
	public void writeBits(int value, int numBits) throws IOException;
	
	
	public int getBitPosition();
	
}
