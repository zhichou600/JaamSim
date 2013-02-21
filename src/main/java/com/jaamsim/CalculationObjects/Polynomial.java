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
package com.jaamsim.CalculationObjects;

import com.sandwell.JavaSimulation.DoubleListInput;
import com.sandwell.JavaSimulation.EntityInput;
import com.sandwell.JavaSimulation.Keyword;

/**
 * The Polynomial entity returns a user-defined polynomial function of its input value.
 * @author Harry King
 *
 */
public class Polynomial extends DoubleCalculation {

	@Keyword(desc = "The DoubleCalculations entity whose present value is the input to the polynomial function.",
	         example = "Polynomial1 Entity { Calc1 }")
	private final EntityInput<DoubleCalculation> entityInput;

	@Keyword(desc = "The list of coefficients for the polynomial function.  For example, inputs c0, c1, c2 give a polynomial" +
			" P(x) = c0 + c1*x^2 + c2*x^3 ",
	         example = "Polynomial1 CoefficientList { 2.0  1.5 }")
	private final DoubleListInput coefficientListInput;

	{
		entityInput = new EntityInput<DoubleCalculation>( DoubleCalculation.class, "Entity", "Key Inputs", null);
		this.addInput( entityInput, true);

		coefficientListInput = new DoubleListInput( "CoefficientList", "Key Inputs", null);
		this.addInput( coefficientListInput, true);
	}

	@Override
	public void update() {

		// Calculate the weighted sum
		double x = entityInput.getValue().getValue();
		double pow = 1.0;
		double val = 0.0;
		for(int i=0; i<coefficientListInput.getValue().size(); i++ ) {
			val += coefficientListInput.getValue().get(i) * pow;
			pow *= x;
		}

		// Set the present value
		this.setValue( val );
		return;
	}
}