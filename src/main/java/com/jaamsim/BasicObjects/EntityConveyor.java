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
package com.jaamsim.BasicObjects;

import java.util.ArrayList;

import com.jaamsim.input.InputAgent;
import com.jaamsim.math.Color4d;
import com.jaamsim.math.Vec3d;
import com.jaamsim.render.HasScreenPoints;
import com.sandwell.JavaSimulation.ColourInput;
import com.sandwell.JavaSimulation.DoubleInput;
import com.sandwell.JavaSimulation.ErrorException;
import com.sandwell.JavaSimulation.InputErrorException;
import com.sandwell.JavaSimulation.Keyword;
import com.sandwell.JavaSimulation.Vec3dListInput;
import com.sandwell.JavaSimulation3D.DisplayEntity;

/**
 * Moves one or more Entities along a path at a constant speed.
 */
public class EntityConveyor extends LinkedComponent implements HasScreenPoints {

	@Keyword(desc = "The travel time for the conveyor.",
	         example = "Conveyor1 TravelTime { 10.0 s }")
	private final DoubleInput travelTimeInput;

    @Keyword(desc = "A list of points in { x, y, z } coordinates defining the line segments that" +
            "make up the arrow.  When two coordinates are given it is assumed that z = 0." ,
             example = "Conveyor1  Points { { 6.7 2.2 m } { 4.9 2.2 m } { 4.9 3.4 m } }")
	private final Vec3dListInput pointsInput;

	@Keyword(desc = "The width of the Arrow line segments in pixels.",
	         example = "Conveyor1 Width { 1 }")
	private final DoubleInput widthInput;

	@Keyword(desc = "The colour of the arrow, defined using a colour keyword or RGB values.",
	         example = "Conveyor1 Color { red }")
	private final ColourInput colorInput;

	private final ArrayList<DisplayEntity> entityList;  // List of the entities being conveyed
	private final ArrayList<Double> startTimeList;  // List of times at which the entities entered the conveyor
	private boolean busy;  // True if there are any DisplayEntities being conveyed
	private double totalLength;  // Graphical length of the conveyor
	private final ArrayList<Double> lengthList;  // Length of each segment of the conveyor
	private final ArrayList<Double> cumLengthList;  // Total length to the end of each segment

	{
		travelTimeInput = new DoubleInput( "TravelTime", "Key Inputs", 0.0d);
		travelTimeInput.setValidRange( 0.0, Double.POSITIVE_INFINITY);
		travelTimeInput.setUnits( "h" );
		this.addInput( travelTimeInput, true);

		ArrayList<Vec3d> defPoints =  new ArrayList<Vec3d>();
		defPoints.add(new Vec3d(0.0d, 0.0d, 0.0d));
		defPoints.add(new Vec3d(1.0d, 0.0d, 0.0d));
		pointsInput = new Vec3dListInput("Points", "Key Inputs", defPoints);
		pointsInput.setValidCountRange( 2, Integer.MAX_VALUE );
		pointsInput.setUnits("m");
		this.addInput(pointsInput, true);

		widthInput = new DoubleInput("Width", "Key Inputs", 1.0d, 1.0d, Double.POSITIVE_INFINITY);
		this.addInput(widthInput, true);

		colorInput = new ColourInput("Color", "Key Inputs", ColourInput.BLACK);
		this.addInput(colorInput, true, "Colour");
	}

	public EntityConveyor() {
		entityList = new ArrayList<DisplayEntity>();
		startTimeList = new ArrayList<Double>();
		lengthList = new ArrayList<Double>();
		cumLengthList = new ArrayList<Double>();
	}

	@Override
	public void validate() {
		super.validate();

		// Confirm that the next entity in the chain has been specified
		if( getNextComponent() == null ) {
			throw new InputErrorException( "The keyword NextEntity must be set." );
		}
	}

	@Override
	public void earlyInit() {
		super.earlyInit();

		// Clear the entities being conveyed
//		int doLoop = entityList.size();
//		for( int i = 0; i < doLoop; i++ ) {
//			DisplayEntity each = entityList.get( i );
//			each.exitRegion();
//			each.kill();
//		}
		entityList.clear();
		startTimeList.clear();
		busy = false;

	    // Initialize the segment length data
		lengthList.clear();
		cumLengthList.clear();
		totalLength = 0.0;
		for( int i = 1; i < pointsInput.getValue().size(); i++ ) {
			// Get length between points
			Vec3d vec = new Vec3d();
			vec.sub3( pointsInput.getValue().get(i), pointsInput.getValue().get(i-1));
			double length = vec.mag3();

			lengthList.add( length);
			totalLength += length;
			cumLengthList.add( totalLength);
		}
	}

	@Override
	public ArrayList<Vec3d> getScreenPoints() {
		return pointsInput.getValue();
	}

	@Override
	public boolean selectable() {
		return true;
	}

	/**
	 *  Inform simulation and editBox of new positions.
	 */
	@Override
	public void dragged(Vec3d dist) {
		ArrayList<Vec3d> vec = new ArrayList<Vec3d>(pointsInput.getValue().size());
		for (Vec3d v : pointsInput.getValue()) {
			vec.add(new Vec3d(v.x + dist.x, v.y + dist.y, v.z + dist.z));
		}

		StringBuilder tmp = new StringBuilder();
		for (Vec3d v : vec) {
			tmp.append(String.format(" { %.3f %.3f %.3f %s }", v.x, v.y, v.z, pointsInput.getUnits()));
		}
		InputAgent.processEntity_Keyword_Value(this, pointsInput, tmp.toString());

		super.dragged(dist);
		setGraphicsDataDirty();
	}

	@Override
	public Color4d getDisplayColour() {
		return colorInput.getValue();
	}

	@Override
	public int getWidth() {
		int ret = widthInput.getValue().intValue();
		if (ret < 1) return 1;
		return ret;

	}

	@Override
	public void addDisplayEntity( DisplayEntity ent ) {

		// Add the entity to the conveyor
		entityList.add( ent );
		startTimeList.add( this.getCurrentTime() );

		// If necessary, wake up the conveyor
		if ( !busy ) {
			this.startProcess( "processEntities" );
		}
	}

	/**
	* Process DisplayEntities from the Queue
	*/
	public void processEntities() {

		// Conveyor should not be busy already
		if( busy ) {
			throw new ErrorException( "Conveyor should not be busy already." );
		}
		busy = true;

		// Loop until the conveyor is empty
		while( entityList.size() > 0 ) {

			// Wait for the first entity to reach the end
			double dt = startTimeList.get(0) + travelTimeInput.getValue() - this.getCurrentTime();
			this.scheduleWait( dt);

			// Remove the entity from the conveyor
			DisplayEntity ent = entityList.remove(0);
			startTimeList.remove(0);

			// Send the entity to the next component
			getNextComponent().addDisplayEntity( ent);
			this.setGraphicsDataDirty();
		}

		// Queue is empty, stop work
		busy = false;
	}

	/**
	 * Return the position coordinates for a given distance along the conveyor.
	 * @param dist = distance along the conveyor.
	 * @return position coordinates
	 */
	private Vec3d getPositionForDistance( double dist) {

		// Find the present segment
		int seg = 0;
		for( int i = 0; i < cumLengthList.size(); i++) {
			if( dist <= cumLengthList.get(i)) {
				seg = i;
				break;
			}
		}

		// Interpolate between the start and end of the segment
		double frac = 0.0;
		if( seg == 0 ) {
			frac = dist / lengthList.get(0);
		}
		else {
			frac = ( dist - cumLengthList.get(seg-1) ) / lengthList.get(seg);
		}
		if( frac < 0.0 )  frac = 0.0;
		else if( frac > 1.0 )  frac = 1.0;

		Vec3d vec = new Vec3d();
		vec.sub3( pointsInput.getValue().get(seg+1), pointsInput.getValue().get(seg));

		vec.scale2( frac);
		vec.add3( pointsInput.getValue().get(seg));
		return vec;
	}

	@Override
	public void updateGraphics( double simTime ) {

		// Loop through the entities on the conveyor
		for( int i = 0; i < entityList.size(); i++) {
			DisplayEntity each = entityList.get( i );

			// Calculate the distance travelled by this entity
			double dist = ( simTime - startTimeList.get(i) ) / travelTimeInput.getValue() * totalLength;

			// Set the position for the entity
			each.setPosition( this.getPositionForDistance( dist) );
		}
	}
}