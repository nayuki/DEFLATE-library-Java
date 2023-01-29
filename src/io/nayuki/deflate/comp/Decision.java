/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;


public interface Decision {
	
	public long getBitLength();
	
	
	public boolean containsUncompressedBlock();
	
	
	public void compressTo(BitOutputStream out, boolean isFinal) throws IOException;
	
}
