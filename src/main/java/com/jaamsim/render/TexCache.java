/*
 * JaamSim Discrete Event Simulation
 * Copyright (C) 2012 Ausenco Engineering Canada Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package com.jaamsim.render;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.media.opengl.GL2GL3;
import javax.media.opengl.GLException;

import com.jogamp.opengl.GLExtensions;

/**
 * A cache that ensures each texture object is only loaded once, looks up textures by URL to there
 * is a chance of a repeated texture if synonymous URLs are used
 * @author Matt.Chudleigh
 *
 */
public class TexCache {

	private static class TexEntry {
		public int texID;
		public boolean hasAlpha;
		public boolean compressed;
		public TexEntry(int id, boolean alpha, boolean compressed) {
			this.texID = id;
			this.hasAlpha = alpha;
			this.compressed = compressed;
		}
	}

	private static class LoadingEntry {
		public int bufferID;
		public String imageURL;
		public boolean hasAlpha;
		public boolean compressed;
		public ByteBuffer data;
		public int width, height;
		public AtomicBoolean done = new AtomicBoolean(false);
		public AtomicBoolean failed = new AtomicBoolean(false);
		public final Object lock = new Object();

		public LoadingEntry(String url, ByteBuffer data, boolean alpha, boolean compressed) {
			this.imageURL = url;
			this.data = data;
			this.hasAlpha = alpha;
			this.compressed = compressed;
		}
	}

	private final Map<String, TexEntry> _texMap = new HashMap<String, TexEntry>();
	private final Map<String, LoadingEntry> _loadingMap = new HashMap<String, LoadingEntry>();

	private Renderer _renderer;

	public static final URL BAD_TEXTURE;
	private int badTextureID = -1;

	public static final int LOADING_TEX_ID = -2;

	static {
		BAD_TEXTURE = TexCache.class.getResource("/resources/images/bad-texture.png");
	}

	public TexCache(Renderer r) {
		_renderer = r;
	}

	public void init(GL2GL3 gl) {
		LoadingEntry badLE = launchLoadImage(gl, BAD_TEXTURE, false, false);
		assert(badLE != null); // We should never fail to load the bad texture

		waitForTex(badLE);
		_loadingMap.remove(BAD_TEXTURE.toString());

		badTextureID = loadGLTexture(gl, badLE);
		assert(badTextureID != -1); // Hopefully OpenGL never naturally returns -1, but I don't think it should
	}

	public int getTexID(GL2GL3 gl, URL imageURL, boolean withAlpha, boolean compressed, boolean waitUntilLoaded) {

		// Scan the list of textures and load any that are ready
		ArrayList<String> loadedStrings = new ArrayList<String>();
		for (Map.Entry<String, LoadingEntry> entry : _loadingMap.entrySet()) {
			LoadingEntry le = entry.getValue();
			if (le.done.get()) {
				loadedStrings.add(entry.getKey());
				int glTexID = loadGLTexture(gl, le);
				_texMap.put(le.imageURL, new TexEntry(glTexID, le.hasAlpha, le.compressed));
			}
		}
		for (String s : loadedStrings) {
			_loadingMap.remove(s);
		}

		String imageURLKey = imageURL.toString();
		if (_texMap.containsKey(imageURLKey)) {

			// There is an entry in the cache, but let's check the other attributes
			TexEntry entry = _texMap.get(imageURLKey);
			boolean found = true;
			if (withAlpha && !entry.hasAlpha) {
				found = false; // This entry does not have an alpha channel
			}
			if (entry.compressed && !compressed) {
				// The entry is compressed, but we requested an uncompressed image
				found = false;
			}

			if (found) {
				return entry.texID;
			}

			// The entry exists, but not as was requested, free the texture so we can reload it
			int[] texIDs = new int[1];
			texIDs[0] = entry.texID;
			gl.glDeleteTextures(1, texIDs, 0);

			_texMap.remove(imageURLKey);
		}

		boolean isLoading = _loadingMap.containsKey(imageURLKey);
		LoadingEntry le = null;
		if (!isLoading) {
			le = launchLoadImage(gl, imageURL, withAlpha, compressed);

			if (le == null) {
				// The image could not be found
				_texMap.put(imageURLKey, new TexEntry(badTextureID, withAlpha, compressed));
				return badTextureID;
			}
		}

		if (!waitUntilLoaded && _renderer.allowDelayedTextures()) {
			return LOADING_TEX_ID;
		}

		waitForTex(le);
		_loadingMap.remove(imageURLKey);

		int glTexID = loadGLTexture(gl, le);
		_texMap.put(le.imageURL, new TexEntry(glTexID, le.hasAlpha, le.compressed));

		return glTexID;
	}

	private LoadingEntry launchLoadImage(GL2GL3 gl, final URL imageURL, final boolean transparent, final boolean compressed) {

		Dimension dim = getImageDimension(imageURL);
		if (dim == null) {
			// Could not load image
			return null;
		}

		// Map an openGL buffer of size width*height*4
		int[] ids = new int[1];
		gl.glGenBuffers(1, ids, 0);

		int bufferSize = dim.width*dim.height*4;
		if (compressed) {
			assert(gl.isExtensionAvailable(GLExtensions.EXT_texture_compression_s3tc));
			// Round width and height up to nearest multiple of 4 (the s3tc block size)
			int width = dim.width;
			if ((width&3)!= 0) {
				width = (width&~3)+4;
			}
			int height = dim.height;
			if ((height&3)!= 0) {
				height = (height&~3)+4;
			}
			bufferSize = width * height / 2;
		}

		ByteBuffer mappedBuffer = null;
			gl.glBindBuffer(GL2GL3.GL_PIXEL_UNPACK_BUFFER, ids[0]);
			gl.glBufferData(GL2GL3.GL_PIXEL_UNPACK_BUFFER, bufferSize, null, GL2GL3.GL_STREAM_READ);
		try {
			mappedBuffer = gl.glMapBuffer(GL2GL3.GL_PIXEL_UNPACK_BUFFER, GL2GL3.GL_WRITE_ONLY);
		} catch (GLException ex) {
			// A GL Exception here is most likely caused by an out of memory, this is recoverable and simply use the bad texture
			return null;
		}
		// Explicitly check for an error (we may not be using a DebugGL implementation, so the exception may not be thrown)
		if (gl.glGetError() != GL2GL3.GL_NO_ERROR) {
			return null;
		}

		gl.glBindBuffer(GL2GL3.GL_PIXEL_UNPACK_BUFFER, 0);

		final LoadingEntry le = new LoadingEntry(imageURL.toString(), mappedBuffer, transparent, compressed);
		le.bufferID = ids[0];

		Runnable loader = new Runnable() {
			@Override
			public void run() {
				BufferedImage img = null;
				try {
					img = ImageIO.read(imageURL);
				}
				catch(Exception e) {
					le.failed.set(true);
					le.done.set(true);
					return;
				}
				if (img == null) {
					le.failed.set(true);
					le.done.set(true);
					return;
				}

				int width = img.getWidth();
				int height = img.getHeight();

				le.width = width;
				le.height = height;

				AffineTransform flipper = new AffineTransform(1, 0,
				                                              0, -1,
				                                              0, height);

				BufferedImage bgr = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g2 = bgr.createGraphics();

				if (!transparent) {
					g2.setColor(Color.WHITE);
				} else {
					g2.setColor(new Color(0, 0, 0, 0));
				}
				g2.fillRect(0, 0, width, height);

				g2.drawImage(img, flipper, null);
				g2.dispose();
				DataBufferInt ints = (DataBufferInt)bgr.getData().getDataBuffer();

				if (compressed) {
					S3TexCompressor comp = new S3TexCompressor();
					IntBuffer intBuffer = (IntBuffer.wrap(ints.getData()));

					ByteBuffer compressed = comp.compress(intBuffer, le.width, le.height);
					le.data.put(compressed);
				} else {
					le.data.asIntBuffer().put(ints.getData());
				}

				le.done.set(true);
				synchronized(le.lock) {
					le.lock.notify();
				}

				_renderer.queueRedraw();
			}
		};

		_loadingMap.put(imageURL.toString(), le);

		Thread loadThread = new Thread(loader);
		loadThread.start();
		return le;
	}

	private int loadGLTexture(GL2GL3 gl, LoadingEntry le) {

		if (le.failed.get()) {
			return badTextureID;
		}

		int[] i = new int[1];
		gl.glGenTextures(1, i, 0);
		int glTexID = i[0];

		gl.glBindTexture(GL2GL3.GL_TEXTURE_2D, glTexID);

		if (le.compressed)
			gl.glTexParameteri(GL2GL3.GL_TEXTURE_2D, GL2GL3.GL_TEXTURE_MIN_FILTER, GL2GL3.GL_LINEAR );
		else
			gl.glTexParameteri(GL2GL3.GL_TEXTURE_2D, GL2GL3.GL_TEXTURE_MIN_FILTER, GL2GL3.GL_LINEAR_MIPMAP_LINEAR );

		gl.glTexParameteri(GL2GL3.GL_TEXTURE_2D, GL2GL3.GL_TEXTURE_MAG_FILTER, GL2GL3.GL_LINEAR );
		gl.glTexParameteri(GL2GL3.GL_TEXTURE_2D, GL2GL3.GL_TEXTURE_WRAP_S, GL2GL3.GL_CLAMP_TO_EDGE);
		gl.glTexParameteri(GL2GL3.GL_TEXTURE_2D, GL2GL3.GL_TEXTURE_WRAP_T, GL2GL3.GL_CLAMP_TO_EDGE);

		gl.glPixelStorei(GL2GL3.GL_UNPACK_ALIGNMENT, 1);

		// Attempt to load to a proxy texture first, then see what happens
		int internalFormat = 0;
		if (le.hasAlpha && le.compressed) {
			// We do not currently support compressed textures with alpha
			assert(false);
			return badTextureID;
		} else if(le.hasAlpha && !le.compressed) {
			internalFormat = GL2GL3.GL_RGBA;
		} else if(!le.hasAlpha && le.compressed) {
			internalFormat = GL2GL3.GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
		} else if(!le.hasAlpha && !le.compressed) {
			internalFormat = GL2GL3.GL_RGB;
		}

		gl.glBindBuffer(GL2GL3.GL_PIXEL_UNPACK_BUFFER, le.bufferID);
		gl.glUnmapBuffer(GL2GL3.GL_PIXEL_UNPACK_BUFFER);

		if (le.compressed) {
			gl.glCompressedTexImage2D(GL2GL3.GL_PROXY_TEXTURE_2D, 0, internalFormat, le.width,
	                le.height, 0, le.data.capacity(), 0);
		} else {
			gl.glTexImage2D(GL2GL3.GL_PROXY_TEXTURE_2D, 0, internalFormat, le.width,
	                le.height, 0, GL2GL3.GL_BGRA, GL2GL3.GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		}

		int[] texWidth = new int[1];
		gl.glGetTexLevelParameteriv(GL2GL3.GL_PROXY_TEXTURE_2D, 0, GL2GL3.GL_TEXTURE_WIDTH, texWidth, 0);
		if (texWidth[0] == 0) {
			// This texture could not be loaded
			return badTextureID;
		}

		if (le.compressed) {
			gl.glCompressedTexImage2D(GL2GL3.GL_TEXTURE_2D, 0, internalFormat, le.width,
			                          le.height, 0, le.data.capacity(), 0);
		} else {
			gl.glTexImage2D(GL2GL3.GL_TEXTURE_2D, 0, internalFormat, le.width,
			                le.height, 0, GL2GL3.GL_BGRA, GL2GL3.GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		}

		// Note we do not let openGL generate compressed mipmaps because it stalls the render thread really badly
		// in theory it could be generated in the worker thread, but not yet

		try {
			if (!le.compressed)
				gl.glGenerateMipmap(GL2GL3.GL_TEXTURE_2D);
		} catch (GLException ex) {
			// It is possible to run out of texture memory here (although it is less likely than
			// running out above
			return badTextureID;
		}

		gl.glBindTexture(GL2GL3.GL_TEXTURE_2D, 0);

		gl.glBindBuffer(GL2GL3.GL_PIXEL_UNPACK_BUFFER, 0);
		i[0] = le.bufferID;
		gl.glDeleteBuffers(1, i, 0);

		return glTexID;
	}

	private Dimension getImageDimension(URL imageURL) {
		ImageInputStream inStream = null;
		try {
			inStream = ImageIO.createImageInputStream(imageURL.openStream());
			Iterator<ImageReader> it = ImageIO.getImageReaders(inStream);
			if (it.hasNext()) {
				ImageReader reader = it.next();
				reader.setInput(inStream);
				Dimension ret = new Dimension(reader.getWidth(0), reader.getHeight(0));
				reader.dispose();
				inStream.close();
				return ret;
			}
		} catch (IOException ex) {
		}

		return null;
	}

	private void waitForTex(LoadingEntry le) {
		synchronized (le.lock) {
			while (!le.done.get()) {
				try {
					le.lock.wait();
				} catch (InterruptedException ex) {}
			}
		}

	}
}