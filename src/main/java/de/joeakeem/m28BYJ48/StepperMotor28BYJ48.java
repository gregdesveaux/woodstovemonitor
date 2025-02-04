package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalOutputProvider;
import com.pi4j.util.Console;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.pi4j.io.gpio.digital.DigitalState.HIGH;
import static com.pi4j.io.gpio.digital.DigitalState.LOW;

public class StepperMotor28BYJ48 {

	private static final Logger LOG = LoggerFactory.getLogger(StepperMotor28BYJ48.class);

	// Pin sequence for controlling the stepper motor
	private final DigitalOutput[] motorPins;
	private final Context pi4j;
	private final int stepDuration;

	private SteppingMethod steppingMethod;

	public enum SteppingMethod {
		WAVE_DRIVE, FULL_STEP, HALF_STEP
	}

	public StepperMotor28BYJ48(Context pi4j, int[] pinNumbers, int stepDuration, SteppingMethod steppingMethod) {
		if (pinNumbers.length != 4) {
			throw new IllegalArgumentException("You must provide exactly 4 GPIO pin numbers.");
		}

		this.pi4j = pi4j;
		this.stepDuration = stepDuration;
		this.steppingMethod = steppingMethod;

		motorPins = new DigitalOutput[4];
		DigitalOutputProvider provider = pi4j.provider("pigpio-digital-output");


		// Provision motor pins
		for (int i = 0; i < pinNumbers.length; i++) {
			motorPins[i] = provider.create(DigitalOutput.newConfigBuilder(pi4j)
					.id("motor-pin-" + i)
					.name("Motor Pin " + i)
					.address(pinNumbers[i])
					.shutdown(LOW)
					.initial(LOW)
					.build());
		}
	}

	public void performDemo(int rotations) {
		LOG.info("Full rotation clockwise in wave drive method...");
		setSteppingMethod(SteppingMethod.WAVE_DRIVE);
		fullRotation(rotations);
		shutdown();
		LOG.info("Done.");
	}

	public void setSteppingMethod(SteppingMethod method) {
		this.steppingMethod = method;
	}

	public void fullRotation(int rotations) {
		step(rotations * 512);
	}

	private void step(int steps) {
		for (int currentStep = 0; currentStep < Math.abs(steps); currentStep++) {
			int sequenceIndex = currentStep % 4;
			writeSequence(sequenceIndex);

			// Pause between steps
			try {
				Thread.sleep(stepDuration);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void writeSequence(int sequenceIndex) {
		// Define sequences for WAVE_DRIVE
		boolean[][] waveDriveSequences = {
				{true, false, false, false},
				{false, true, false, false},
				{false, false, true, false},
				{false, false, false, true}
		};

		// Apply sequence to pins
		for (int i = 0; i < motorPins.length; i++) {
			motorPins[i].state(waveDriveSequences[sequenceIndex][i] ? HIGH : LOW);
		}
	}

	public void shutdown() {
		for (DigitalOutput pin : motorPins) {
			pin.state(LOW); // Set all pins to LOW
			pin.shutdown(pi4j); // Release the pi
		}
	}


}