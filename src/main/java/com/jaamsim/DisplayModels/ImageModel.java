/*
 * JaamSim Discrete Event Simulation
 * Copyright (C) 2013 Ausenco Engineering Canada Inc.
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
package com.jaamsim.DisplayModels;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;

import com.jaamsim.input.Keyword;
import com.jaamsim.math.Transform;
import com.jaamsim.math.Vec3d;
import com.jaamsim.render.DisplayModelBinding;
import com.jaamsim.render.ImageProxy;
import com.jaamsim.render.OverlayTextureProxy;
import com.jaamsim.render.RenderProxy;
import com.jaamsim.render.TexCache;
import com.jaamsim.render.VisibilityInfo;
import com.sandwell.JavaSimulation.BooleanInput;
import com.sandwell.JavaSimulation.Entity;
import com.sandwell.JavaSimulation.ErrorException;
import com.sandwell.JavaSimulation.FileInput;
import com.sandwell.JavaSimulation.IntegerVector;
import com.sandwell.JavaSimulation3D.DisplayEntity;
import com.sandwell.JavaSimulation3D.OverlayImage;

public class ImageModel extends DisplayModel {

	@Keyword(description = "The file containing the image to show, valid formats are: BMP, JPG, PNG, PCX, GIF.",
	         example = "Ship3DModel ImageFile { ..\\images\\CompanyIcon.png }")
	private final FileInput imageFile;

	@Keyword(description = "Indicates the loaded image has an alpha channel (transparency information) that should be used",
	         example = "CompanyLogo Transparent { TRUE }")
	private final BooleanInput transparent;

	@Keyword(description = "Indicates the loaded image should use texture compression in video memory",
	         example = "WorldMap CompressedTexture { TRUE }")
	private final BooleanInput compressedTexture;

	private static final String[] validFileExtensions;
	static {
		validFileExtensions = new String[5];
		validFileExtensions[0] = "BMP";
		validFileExtensions[1] = "JPG";
		validFileExtensions[2] = "PNG";
		validFileExtensions[3] = "PCX";
		validFileExtensions[4] = "GIF";
	}

	{
		imageFile = new FileInput( "ImageFile", "DisplayModel", null );
		imageFile.setValidExtensions(validFileExtensions);
		this.addInput( imageFile, true);

		transparent = new BooleanInput("Transparent", "DisplayModel", false);
		this.addInput(transparent, true);

		compressedTexture = new BooleanInput("CompressedTexture", "DisplayModel", false);
		this.addInput(compressedTexture, true);

	}

	public ImageModel() {}

	@Override
	public DisplayModelBinding getBinding(Entity ent) {
		if (ent instanceof OverlayImage) {
			return new OverlayBinding(ent, this);
		}
		return new Binding(ent, this);
	}

	@Override
	public boolean canDisplayEntity(Entity ent) {
		return ent instanceof DisplayEntity;
	}

	public URI getImageFile() {
		return imageFile.getValue();
	}

	private class Binding extends DisplayModelBinding {

		private ArrayList<RenderProxy> cachedProxies;

		private DisplayEntity dispEnt;

		private Transform transCache;
		private Vec3d scaleCache;
		private URI imageCache;
		private boolean compressedCache;
		private boolean transparentCache;
		private VisibilityInfo viCache;

		public Binding(Entity ent, DisplayModel dm) {
			super(ent, dm);
			dispEnt = (DisplayEntity)observee;
		}

		private void updateCache(double simTime) {
			Transform trans;
			Vec3d scale;
			long pickingID;
			if (dispEnt == null) {
				trans = Transform.ident;
				scale = DisplayModel.ONES;
				pickingID = 0;
			} else {
				trans = dispEnt.getGlobalTrans(simTime);
				scale = dispEnt.getSize();
				scale.mul3(getModelScale());
				pickingID = dispEnt.getEntityNumber();
			}

			URI imageName = imageFile.getValue();
			Boolean transp = transparent.getValue();
			Boolean compressed = compressedTexture.getValue();

			VisibilityInfo vi = getVisibilityInfo();

			boolean dirty = false;

			dirty = dirty || !compare(transCache, trans);
			dirty = dirty || dirty_vec3d(scaleCache, scale);
			dirty = dirty || !compare(imageCache, imageName);
			dirty = dirty || transparentCache != transp;
			dirty = dirty || compressedCache != compressed;
			dirty = dirty || !compare(viCache, vi);

			transCache = trans;
			scaleCache = scale;
			imageCache = imageName;
			transparentCache = transp;
			compressedCache = compressed;
			viCache = vi;

			if (cachedProxies != null && !dirty) {
				// Nothing changed
				registerCacheHit("ImageModel");
				return;
			}

			registerCacheMiss("ImageModel");
			// Gather some inputs

			cachedProxies = new ArrayList<RenderProxy>();
			try {
				cachedProxies.add(new ImageProxy(imageName.toURL(), trans,
				                       scale, transp, compressed, vi, pickingID));
			} catch (MalformedURLException e) {
				cachedProxies.add(new ImageProxy(TexCache.BAD_TEXTURE, trans, scale,
				                                 transp, compressed, vi, pickingID));
			}

		}

		@Override
		public void collectProxies(double simTime, ArrayList<RenderProxy> out) {
			// This is slightly quirky behaviour, as a null entity will be shown because we use that for previews
			if (dispEnt == null || !dispEnt.getShow()) {
				return;
			}

			updateCache(simTime);

			out.addAll(cachedProxies);
		}
	}

	private class OverlayBinding extends DisplayModelBinding {

		private OverlayImage imageObservee;

		private OverlayTextureProxy cachedProxy = null;

		private URI filenameCache;
		private IntegerVector posCache;
		private IntegerVector sizeCache;
		private boolean alignBottomCache;
		private boolean alignRightCache;
		private VisibilityInfo viCache;

		public OverlayBinding(Entity ent, DisplayModel dm) {
			super(ent, dm);
			try {
				imageObservee = (OverlayImage)observee;
			} catch (ClassCastException e) {
				// The observee is not a display entity
				imageObservee = null;
			}
		}

		@Override
		public void collectProxies(double simTime, ArrayList<RenderProxy> out) {
			if (imageObservee == null || !imageObservee.getShow()) {
				return;
			}

			URI filename = imageFile.getValue();
			IntegerVector pos = imageObservee.getScreenPosition();
			IntegerVector size = imageObservee.getImageSize();

			boolean alignRight = imageObservee.getAlignRight();
			boolean alignBottom = imageObservee.getAlignBottom();

			VisibilityInfo vi = getVisibilityInfo();

			boolean dirty = false;

			dirty = dirty || !compare(filenameCache, filename);
			dirty = dirty || !compare(posCache, pos);
			dirty = dirty || !compare(sizeCache, size);
			dirty = dirty || alignRightCache != alignRight;
			dirty = dirty || alignBottomCache != alignBottom;
			dirty = dirty || !compare(viCache, vi);

			filenameCache = filename;
			posCache = pos;
			sizeCache = size;
			alignRightCache = alignRight;
			alignBottomCache = alignBottom;
			viCache = vi;

			if (cachedProxy != null && !dirty) {
				// Nothing changed

				out.add(cachedProxy);
				registerCacheHit("OverlayImage");
				return;
			}

			registerCacheMiss("OverlayImage");

			try {
				cachedProxy = new OverlayTextureProxy(pos.get(0), pos.get(1), size.get(0), size.get(1),
				                                      filename.toURL(),
				                                      transparent.getValue(), false,
				                                      alignRight, alignBottom, vi);

				out.add(cachedProxy);
			} catch (MalformedURLException ex) {
				cachedProxy = null;
			} catch (ErrorException ex) {
				cachedProxy = null;
			}
		}

		@Override
		protected void collectSelectionBox(double simTime, ArrayList<RenderProxy> out) {
			// No selection widgets for now
		}
	}

}
