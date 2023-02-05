/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;


public final class GzipOutputStream extends OutputStream {
	
	/*---- Fields ----*/
	
	private DeflaterOutputStream output;
	
	private CRC32 checksum = new CRC32();
	private long uncompressedLength = 0;
	
	
	
	/*---- Constructors ----*/
	
	public GzipOutputStream(OutputStream out, GzipMetadata meta) throws IOException {
		this(new DeflaterOutputStream(out), meta);
	}
	
	
	public GzipOutputStream(DeflaterOutputStream out, GzipMetadata meta) throws IOException {
		Objects.requireNonNull(out);
		Objects.requireNonNull(meta);
		meta.write(out.getUnderlyingStream());
		output = out;
	}
	
	
	
	/*---- Methods ----*/
	
	@Override public void write(int b) throws IOException {
		write(new byte[]{(byte)b});
	}
	
	
	@Override public void write(byte[] b, int off, int len) throws IOException {
		if (checksum == null)
			throw new IllegalStateException("Stream already ended");
		output.write(b, off, len);
		checksum.update(b, off, len);
		uncompressedLength += len;
	}
	
	
	public void finish() throws IOException {
		if (checksum == null)
			throw new IllegalStateException("Stream already ended");
		output.finish();
		DataOutput dout = new DataOutputStream(output.getUnderlyingStream());
		dout.writeInt(Integer.reverseBytes((int)checksum.getValue()));
		checksum = null;
		dout.writeInt(Integer.reverseBytes((int)uncompressedLength));
	}
	
	
	@Override public void close() throws IOException {
		if (checksum != null)
			finish();
		output.close();
		output = null;
	}
	
}
