/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;


public enum StaticHuffman implements Strategy {
	
	SINGLETON;
	
	
	@Override public Decision decide(byte[] b, int off, int historyLen, int dataLen) {
		final long bitLength;
		{
			long bitLen = 1 + 2;
			int index = off + historyLen;
			final int end = index + dataLen;
			for (; index < end; index++)
				bitLen += (b[index] & 0xFF) < 144 ? 8 : 9;
			bitLen += 7;
			bitLength = bitLen;
		}
		
		
		return new Decision() {
			@Override public long getBitLength() {
				return bitLength;
			}
			
			@Override public boolean containsUncompressedBlock() {
				return false;
			}
			
			@Override public void compressTo(BitOutputStream out, boolean isFinal) throws IOException {
				out.writeBits(isFinal ? 1 : 0, 1);
				out.writeBits(1, 2);
				
				int index = off + historyLen;
				final int end = index + dataLen;
				for (; index < end; index++) {
					int sym = b[index] & 0xFF;
					out.writeBits(HUFFMAN_CODES[sym], (sym < 144 ? 8 : 9));
				}
				out.writeBits(0, 7);  // Symbol 256 (end of block)
			}
		};
	}
	
	
	private static short[] HUFFMAN_CODES = new short[286];
	
	static {
		int i = 0;
		for (; i < 144; i++) HUFFMAN_CODES[i] = (short)(Integer.reverse(i -   0 +  48) >>> (32 - 8));
		for (; i < 256; i++) HUFFMAN_CODES[i] = (short)(Integer.reverse(i - 144 + 400) >>> (32 - 9));
		for (; i < 280; i++) HUFFMAN_CODES[i] = (short)(Integer.reverse(i - 256 +   0) >>> (32 - 7));
		for (; i < 286; i++) HUFFMAN_CODES[i] = (short)(Integer.reverse(i - 280 + 192) >>> (32 - 8));
	}
	
}
