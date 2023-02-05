/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.InputStream;
import java.util.Objects;


final class StringInputStream extends InputStream {
	
	/*---- Fields ----*/
	
	private final String bits;
	private int index = 0;
	private int mark = -1;
	
	
	
	/*---- Constructor ----*/
	
	public StringInputStream(String s) {
		Objects.requireNonNull(s);
		if (!s.matches("[01]*"))
			throw new IllegalArgumentException("String has characters other than 0 and 1");
		if (s.length() % 8 != 0)
			throw new IllegalArgumentException("String length not a multiple of 8");
		bits = s;
	}
	
	
	
	/*---- Methods ----*/
	
	@Override public int read() {
		if (index >= bits.length())
			return -1;
		int result = Integer.parseInt(bits.substring(index, index + 8), 2);
		result = Integer.reverse(result) >>> 24;
		index += 8;
		return result;
	}
	
	
	@Override public boolean markSupported() {
		return true;
	}
	
	
	@Override public void mark(int limit) {
		mark = index;
	}
	
	
	@Override public void reset() {
		if (mark == -1)
			throw new IllegalStateException("No mark set");
		index = mark;
	}
	
}
