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
import java.util.zip.Adler32;


public final class ZlibOutputStream extends OutputStream {
	
	/*---- Fields ----*/
	
	private DeflaterOutputStream output;
	
	private Adler32 checksum = new Adler32();
	
	
	
	/*---- Constructors ----*/
	
	public ZlibOutputStream(OutputStream out, ZlibMetadata meta) throws IOException {
		this(new DeflaterOutputStream(out), meta);
	}
	
	
	public ZlibOutputStream(DeflaterOutputStream out, ZlibMetadata meta) throws IOException {
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
	}
	
	
	public void finish() throws IOException {
		if (checksum == null)
			throw new IllegalStateException("Stream already ended");
		output.finish();
		DataOutput dout = new DataOutputStream(output.getUnderlyingStream());
		dout.writeInt((int)checksum.getValue());
		checksum = null;
	}
	
	
	@Override public void close() throws IOException {
		if (checksum != null)
			finish();
		output.close();
		output = null;
	}
	
}
