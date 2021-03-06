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

import com.jaamsim.input.Keyword;
import com.jaamsim.input.Output;
import com.jaamsim.units.DimensionlessUnit;
import com.jaamsim.units.RateUnit;
import com.sandwell.JavaSimulation.EntityInput;
import com.sandwell.JavaSimulation.InputErrorException;
import com.sandwell.JavaSimulation3D.DisplayEntity;

/**
 * LinkedComponents are used to form a chain of components that process DisplayEntities that pass through the system.
 * Sub-classes for EntityGenerator, Server, and EntitySink.
 */
public abstract class LinkedComponent extends DisplayEntity {

	@Keyword(description = "The next object to which the processed DisplayEntity is passed.",
	         example = "EntityGenerator-1 NextComponent { Server-1 }")
	protected final EntityInput<LinkedComponent> nextComponentInput;

	private int numberAdded;     // Number of entities added to this component from upstream
	private int numberProcessed; // Number of entities processed by this component

	{
		nextComponentInput = new EntityInput<LinkedComponent>( LinkedComponent.class, "NextComponent", "Key Inputs", null);
		this.addInput( nextComponentInput, true);
	}

	@Override
	public void validate() {
		super.validate();

		// Confirm that the next entity in the chain has been specified
		if( ! nextComponentInput.getHidden() &&	nextComponentInput.getValue() == null ) {
			throw new InputErrorException( "The keyword NextComponent must be set." );
		}
	}

	@Override
	public void earlyInit() {
		super.earlyInit();
		numberAdded = 0;
		numberProcessed = 0;
	}

	public void addDisplayEntity( DisplayEntity ent ) {
		numberAdded++;
	}

	public void sendToNextComponent(DisplayEntity ent) {
		if( nextComponentInput.getValue() != null )
			nextComponentInput.getValue().addDisplayEntity(ent);

		numberProcessed++;
	}

	@Output(name = "NumberAdded",
	 description = "The number of entities received from upstream.",
	    unitType = DimensionlessUnit.class)
	public Integer getNumberAdded(double simTime) {
		return numberAdded;
	}

	@Output(name = "NumberProcessed",
	 description = "The number of entities processed by this component.",
	    unitType = DimensionlessUnit.class)
	public Integer getNumberProcessed(double simTime) {
		return numberProcessed;
	}
	@Output(name = "ProcessingRate",
	 description = "The number of entities processed per unit time by this component.",
	    unitType = RateUnit.class)
	public Double getProcessingRate( double simTime) {
		return numberProcessed/simTime;
	}

}
