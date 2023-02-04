/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.CRC32;


public final class GzipInputStream extends InputStream {
	
	/*---- Fields ----*/
	
	private InputStream rawInput;
	private InputStream decompressedInput;
	
	private final GzipMetadata metadata;
	
	private long decompressedLength = 0;
	private CRC32 checksum = new CRC32();
	private boolean hasEnded = false;
	
	
	
	/*---- Constructor ----*/
	
	public GzipInputStream(InputStream in) throws IOException {
		Objects.requireNonNull(in);
		metadata = GzipMetadata.read(in);
		if (!in.markSupported())
			in = new BufferedInputStream(in);
		rawInput = in;
		decompressedInput = new InflaterInputStream(in, true);
	}
	
	
	
	/*---- Methods ----*/
	
	public GzipMetadata getMetadata() {
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
		int result = decompressedInput.read(b, off, len);
		if (result != -1) {
			decompressedLength += result;
			checksum.update(b, off, result);
		} else if (!hasEnded) {
			hasEnded = true;
			DataInput din = new DataInputStream(rawInput);
			if ((int)checksum.getValue() != Integer.reverseBytes(din.readInt()))
				throw new DataFormatException("Decompression CRC-32 mismatch");
			if ((int)decompressedLength != Integer.reverseBytes(din.readInt()))
				throw new DataFormatException("Decompressed size mismatch");
		}
		return result;
	}
	
	
	@Override public void close() throws IOException {
		rawInput.close();
		rawInput = null;
		decompressedInput = null;
	}
	
}
