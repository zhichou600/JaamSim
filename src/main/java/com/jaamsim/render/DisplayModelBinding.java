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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.jaamsim.DisplayModels.DisplayModel;
import com.jaamsim.controllers.RenderManager;
import com.jaamsim.math.Color4d;
import com.jaamsim.math.Mat4d;
import com.jaamsim.math.Transform;
import com.jaamsim.math.Vec3d;
import com.jaamsim.math.Vec4d;
import com.sandwell.JavaSimulation.ChangeWatcher;
import com.sandwell.JavaSimulation.ColourInput;
import com.sandwell.JavaSimulation.Entity;
import com.sandwell.JavaSimulation3D.DisplayEntity;

/**
 * Represents the One-to-one mapping of DisplayModels to Entities
 * Any graphical caching goes in here, while configuration information goes in the DisplayModel
 * @author matt.chudleigh
 *
 */
public abstract class DisplayModelBinding {

	protected Entity observee;
	protected DisplayModel dm;

	private static final Color4d MINT = ColourInput.getColorWithName("mint");

	private static final boolean _saveCacheMissData = false;
	private static HashMap<String, Integer> cacheMissData = new HashMap<String, Integer>();

	//protected DisplayEntity _dispObservee;

	private ChangeWatcher.Tracker _selectionTracker;

	private List<Vec4d> handlePoints = null;

	private List<Vec4d> rotateHandlePoints = null;

	private final static ArrayList<Vec4d> HANDLE_POINTS;
	private final static ArrayList<Vec4d> ROTATE_POINTS;

	protected static int _cacheHits = 0;
	protected static int _cacheMisses = 0;


	static {
		// NOTE: the order of the points corresponds to the list of static picking IDs in RenderManager,
		// both need to be changed together
		HANDLE_POINTS = new ArrayList<Vec4d>(8);
		// Sides
		HANDLE_POINTS.add(new Vec4d( 0.5,    0, 0, 1.0d));
		HANDLE_POINTS.add(new Vec4d(-0.5,    0, 0, 1.0d));
		HANDLE_POINTS.add(new Vec4d(   0,  0.5, 0, 1.0d));
		HANDLE_POINTS.add(new Vec4d(   0, -0.5, 0, 1.0d));

		// Corners
		HANDLE_POINTS.add(new Vec4d( 0.5,  0.5, 0, 1.0d));
		HANDLE_POINTS.add(new Vec4d( 0.5, -0.5, 0, 1.0d));
		HANDLE_POINTS.add(new Vec4d(-0.5,  0.5, 0, 1.0d));
		HANDLE_POINTS.add(new Vec4d(-0.5, -0.5, 0, 1.0d));

		ROTATE_POINTS = new ArrayList<Vec4d>(2);
		// Sides
		ROTATE_POINTS.add(new Vec4d(1.0, 0, 0, 1.0d));
		ROTATE_POINTS.add(new Vec4d(0.5, 0, 0, 1.0d));
	}

	public DisplayModelBinding(Entity ent, DisplayModel dm) {
		this.observee = ent;
		this.dm = dm;
		if (observee instanceof DisplayEntity)
		{
			_selectionTracker = ((DisplayEntity)ent).getGraphicsChangeTracker();
		}
	}

	public abstract void collectProxies(double simTime, ArrayList<RenderProxy> out);

	public boolean isBoundTo(Entity ent) {
		return ent == observee;
	}

	private void updatePoints(double simTime) {

		if (!(observee instanceof DisplayEntity))
		{
			return;
		}
		DisplayEntity de = (DisplayEntity)observee;
		// Convert the points to world space

		Transform trans = de.getGlobalTrans(simTime);
		Vec3d scale = de.getSize();
		scale.mul3(dm.getModelScale());

		Mat4d mat = new Mat4d(trans.getMat4dRef());
		mat.scaleCols3(scale);

		handlePoints = RenderUtils.transformPoints(mat, HANDLE_POINTS, 0);

		rotateHandlePoints = RenderUtils.transformPoints(mat, ROTATE_POINTS, 0);
	}

	// Collect the proxies for the selection box
	public void collectSelectionProxies(double simTime, ArrayList<RenderProxy> out) {
		collectSelectionBox(simTime, out);
	}

	// This is exposed differently than above, because of the weird type heirarchy around
	// ScreenPointsObservers. This can't just be overloaded, because sometime we want it back....
	protected void collectSelectionBox(double simTime, ArrayList<RenderProxy> out) {

		if (!(observee instanceof DisplayEntity))
		{
			return;
		}

		DisplayEntity de = (DisplayEntity)observee;
		Transform trans = de.getGlobalTrans(simTime);
		Vec3d scale = de.getSize();
		scale.mul3(dm.getModelScale());

		PolygonProxy outline = new PolygonProxy(RenderUtils.RECT_POINTS, trans, scale,
		                                        MINT, true, 1,
		                                        getVisibilityInfo(), RenderManager.MOVE_PICK_ID);
		outline.setHoverColour(ColourInput.LIGHT_GREY);
		out.add(outline);

		if (handlePoints == null || _selectionTracker.checkAndClear()) {
			updatePoints(simTime);
		}

		for (int i = 0; i < 8; ++i) {

			List<Vec4d> pl = new ArrayList<Vec4d>(1);

			pl.add(handlePoints.get(i));
			PointProxy point = new PointProxy(pl, ColourInput.GREEN, 8, getVisibilityInfo(), RenderManager.RESIZE_POSX_PICK_ID - i);
			point.setHoverColour(ColourInput.LIGHT_GREY);
			out.add(point);
		}

		// Add the rotate handle
		List<Vec4d> pl = new ArrayList<Vec4d>(1);
		pl.add(new Vec4d(rotateHandlePoints.get(0)));
		PointProxy point = new PointProxy(pl, ColourInput.GREEN, 8, getVisibilityInfo(), RenderManager.ROTATE_PICK_ID);
		point.setHoverColour(ColourInput.LIGHT_GREY);
		out.add(point);

		LineProxy rotateLine = new LineProxy(rotateHandlePoints, MINT, 1, getVisibilityInfo(), RenderManager.ROTATE_PICK_ID);
		rotateLine.setHoverColour(ColourInput.LIGHT_GREY);
		out.add(rotateLine);
	}

	public static int getCacheHits() {
		return _cacheHits;
	}

	public static int getCacheMisses() {
		return _cacheMisses;
	}
	public static void clearCacheCounters() {
		_cacheHits = 0;
		_cacheMisses = 0;
	}

	public static void clearCacheMissData() {
		cacheMissData.clear();
	}

	private static final boolean saveCacheMissData() {
		return _saveCacheMissData;
	}

	public static void registerCacheMiss(String type) {
		if (!saveCacheMissData()) {
			return;
		}

		int count = 1;
		if (cacheMissData.containsKey(type)) {
			count = cacheMissData.get(type) + 1;
		}
		cacheMissData.put(type, count);
	}

	public static HashMap<String, Integer> getCacheMissData() {
		return cacheMissData;
	}

	public VisibilityInfo getVisibilityInfo() {

		return dm.getVisibilityInfo();
	}


}