/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;


final class CountingBitOutputStream implements BitOutputStream {
	
	private long length = 0;
	
	
	@Override public void writeBits(int value, int numBits) throws IOException {
		length += numBits;
	}
	
	
	@Override public int getBitPosition() {
		return (int)length % 8;
	}
	
	
	public long getBitLength() {
		return length;
	}
	
}
