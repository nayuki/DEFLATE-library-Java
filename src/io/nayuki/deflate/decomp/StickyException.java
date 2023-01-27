/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.decomp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;


// A saved exception that is thrown on every read() or detach().
public record StickyException(
		InputStream input,
		IOException exception)
	implements State {
	
	
	public StickyException {
		Objects.requireNonNull(input);
		Objects.requireNonNull(exception);
	}
	
}
