/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.util.Objects;


@SuppressWarnings("serial")
public final class DataFormatException extends RuntimeException {
	
	/*---- Field ----*/
	
	private final Reason reason;
	
	
	/*---- Constructor ----*/
	
	public DataFormatException(Reason rsn, String msg) {
		super(msg);
		reason = Objects.requireNonNull(rsn);
	}
	
	
	/* 
	 * Always throws, never returns. Use this shorter form whenever possible:
	 *     DataFormatException.throwUnexpectedEnd();
	 * Otherwise if definite control flow manipulation is needed, then use:
	 *     int foo;
	 *     try {
	 *         foo = bar();
	 *     } catch (EOFException e) {
	 *         throw DataFormatException.throwUnexpectedEnd();
	 *     }
	 *     print(foo);
	 */
	public static DataFormatException throwUnexpectedEnd() {
		throw new DataFormatException(
			Reason.UNEXPECTED_END_OF_STREAM,
			"Unexpected end of stream");
	}
	
	
	/*---- Methods ----*/
	
	public Reason getReason() {
		return reason;
	}
	
	
	
	/*---- Enumeration ----*/
	
	public enum Reason {
		UNEXPECTED_END_OF_STREAM,
		RESERVED_BLOCK_TYPE,
		UNCOMPRESSED_BLOCK_LENGTH_MISMATCH,
		HUFFMAN_CODE_UNDER_FULL,
		HUFFMAN_CODE_OVER_FULL,
		NO_PREVIOUS_CODE_LENGTH_TO_COPY,
		CODE_LENGTH_CODE_OVER_FULL,
		END_OF_BLOCK_CODE_ZERO_LENGTH,
		RESERVED_LENGTH_SYMBOL,
		RESERVED_DISTANCE_SYMBOL,
		LENGTH_ENCOUNTERED_WITH_EMPTY_DISTANCE_CODE,
		COPY_FROM_BEFORE_DICTIONARY_START,
		
		HEADER_CHECKSUM_MISMATCH,
		UNSUPPORTED_COMPRESSION_METHOD,
		DECOMPRESSED_CHECKSUM_MISMATCH,
		DECOMPRESSED_SIZE_MISMATCH,
		
		GZIP_INVALID_MAGIC_NUMBER,
		GZIP_RESERVED_FLAGS_SET,
		GZIP_UNSUPPORTED_OPERATING_SYSTEM,
	}
	
}
