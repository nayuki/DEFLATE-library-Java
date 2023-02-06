/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.Adler32;
import io.nayuki.deflate.DataFormatException.Reason;


public final class ZlibInputStream extends InputStream {
	
	/*---- Fields ----*/
	
	private InputStream rawInput;
	private InputStream decompressedInput;
	
	private final ZlibMetadata metadata;
	
	private Adler32 checksum = new Adler32();
	
	
	
	/*---- Constructor ----*/
	
	public ZlibInputStream(InputStream in) throws IOException {
		Objects.requireNonNull(in);
		metadata = ZlibMetadata.read(in);
		if (!in.markSupported())
			in = new BufferedInputStream(in);
		rawInput = in;
		decompressedInput = new InflaterInputStream(in, true);
	}
	
	
	
	/*---- Methods ----*/
	
	public ZlibMetadata getMetadata() {
		return metadata;
	}
	
	
	@Override public int read() throws IOException {
		var b = new byte[1];
		return switch (read(b)) {
			case  1 -> b[0] & 0xFF;
			case -1 -> -1;  // EOF
			default -> throw new AssertionError("Unreachable value");
		};
	}
	
	
	@Override public int read(byte[] b, int off, int len) throws IOException {
		if (decompressedInput == null)
			return -1;
		int result = decompressedInput.read(b, off, len);
		if (result != -1)
			checksum.update(b, off, result);
		else {
			decompressedInput = null;
			int expectChecksum;
			try {
				expectChecksum = new DataInputStream(rawInput).readInt();
			} catch (EOFException e) {
				throw new DataFormatException(Reason.UNEXPECTED_END_OF_STREAM, "Unexpected end of stream");
			}
			if ((int)checksum.getValue() != expectChecksum)
				throw new DataFormatException(Reason.DECOMPRESSED_CHECKSUM_MISMATCH, "Decompression Adler-32 mismatch");
			checksum = null;
		}
		return result;
	}
	
	
	@Override public void close() throws IOException {
		rawInput.close();
		rawInput = null;
		decompressedInput = null;
		checksum = null;
	}
	
}
