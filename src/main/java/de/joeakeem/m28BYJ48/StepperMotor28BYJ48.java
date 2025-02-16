package de.joeakeem.m28BYJ48;

import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputProvider;
import com.pi4j.io.gpio.digital.DigitalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.pi4j.io.gpio.digital.DigitalState.HIGH;
import static com.pi4j.io.gpio.digital.DigitalState.LOW;

public class StepperMotor28BYJ48 {

	private static final Logger LOG = LoggerFactory.getLogger(StepperMotor28BYJ48.class);

	private final Context pi4j;
	DigitalOutput stepPin;
	DigitalOutput dirPin;
	DigitalOutput enablePin;



	public StepperMotor28BYJ48(Context pi4j) {


		this.pi4j = pi4j;




		// Provision motor pins
		stepPin = pi4j.create(
				DigitalOutput.newConfigBuilder(pi4j)
						.id("STEP_PIN")
						.name("Stepper Step Pin")
						.address(18)  // BCM pin number for STEP; adjust as needed
						.shutdown(DigitalState.LOW)
						.initial(DigitalState.LOW)
						.provider("pigpio-digital-output")  // using pigpio provider
						.build()
		);

		// Create the digital output for the DIR (direction) pin (GPIO23)
		dirPin = pi4j.create(
				DigitalOutput.newConfigBuilder(pi4j)
						.id("DIR_PIN")
						.name("Stepper Direction Pin")
						.address(23)  // BCM pin number for DIR; adjust as needed
						.shutdown(DigitalState.LOW)
						.initial(DigitalState.LOW)
						.provider("pigpio-digital-output")  // using pigpio provider
						.build()
		);
		 enablePin = pi4j.create(
				DigitalOutput.newConfigBuilder(pi4j)
						.id("ENABLE_PIN")
						.name("Stepper Enable Pin")
						.address(24)  // BCM pin number for DIR; adjust as needed
						.shutdown(HIGH)
						.initial(HIGH)
						.provider("pigpio-digital-output")  // using pigpio provider
						.build()
		);
	}

    public void moveDamper(int rotations, int direction) {
		LOG.info("moving damper "+ rotations+" rotations");
		if(direction==1){
			dirPin.high();  //close
		}else{
			dirPin.low(); // open
		}
		enablePin.low();
		int stepDelay=10;
		for (int i = 0; i < rotations; i++) {
			// Pulse the STEP pin HIGH, then LOW to trigger a single step
			stepPin.high();
			sleepMillis(stepDelay);
			stepPin.low();
			sleepMillis(stepDelay);
		}
		enablePin.high();
		shutdown();
		LOG.info("Done.");
	}
	private static void sleepMillis(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			ex.printStackTrace();
		}
	}



	public void shutdown() {

			stepPin.state(LOW); // Set all pins to LOW
			stepPin.shutdown(pi4j); // Release the pi
		dirPin.state(LOW); // Set all pins to LOW
		dirPin.shutdown(pi4j); // Release the pi
		enablePin.state(HIGH); // Set all pins to LOW
		enablePin.shutdown(pi4j); // Release the pi

	}


}