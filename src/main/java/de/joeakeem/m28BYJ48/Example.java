package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import de.joeakeem.m28BYJ48.StepperMotor28BYJ48.SteppingMethod;

/**
 * Hello world!
 *
 */
public class Example 
{
    public static void main( String[] args )
    {
        int[] pins={14,15,18,23};
        Context pi4j= Pi4J.newAutoContext();
    	StepperMotor28BYJ48 stepperMotor = new
    			StepperMotor28BYJ48(pi4j,pins, 10, SteppingMethod.FULL_STEP);
    	int rotations=Integer.parseInt(args[0]);
    	stepperMotor.performDemo(rotations);
    }
}
