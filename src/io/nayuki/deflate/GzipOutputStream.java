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
	
	private OutputStream rawOutput;
	private DeflaterOutputStream compressingOutput;
	
	private long uncompressedLength = 0;
	private CRC32 checksum = new CRC32();
	
	
	
	/*---- Constructors ----*/
	
	public GzipOutputStream(OutputStream out, GzipMetadata meta) throws IOException {
		this(out, meta, new DeflaterOutputStream(out));
	}
	
	
	public GzipOutputStream(OutputStream out, GzipMetadata meta, DeflaterOutputStream compOut) throws IOException {
		Objects.requireNonNull(out);
		Objects.requireNonNull(meta);
		Objects.requireNonNull(compOut);
		meta.write(out);
		rawOutput = out;
		compressingOutput = compOut;
	}
	
	
	
	/*---- Methods ----*/
	
	@Override public void write(int b) throws IOException {
		write(new byte[]{(byte)b});
	}
	
	
	@Override public void write(byte[] b, int off, int len) throws IOException {
		if (compressingOutput == null)
			throw new IllegalStateException("Stream already ended");
		compressingOutput.write(b, off, len);
		uncompressedLength += len;
		checksum.update(b, off, len);
	}
	
	
	public void finish() throws IOException {
		if (compressingOutput == null)
			throw new IllegalStateException("Stream already ended");
		compressingOutput.finish();
		compressingOutput = null;
		DataOutput dout = new DataOutputStream(rawOutput);
		dout.writeInt(Integer.reverseBytes((int)checksum.getValue()));
		checksum = null;
		dout.writeInt(Integer.reverseBytes((int)uncompressedLength));
	}
	
	
	@Override public void close() throws IOException {
		if (compressingOutput != null)
			finish();
		rawOutput.close();
		rawOutput = null;
	}
	
}
