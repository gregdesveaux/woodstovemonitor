#!/usr/bin/env python3
import RPi.GPIO as GPIO
import time

# Use BCM numbering for the GPIO pins
GPIO.setmode(GPIO.BCM)

# Define the GPIO pins (adjust these numbers as necessary)
ENABLE_PIN = 24  # Connected to the A4988 ENABLE pin
STEP_PIN   = 18  # Connected to the A4988 STEP pin
DIR_PIN    = 23  # Connected to the A4988 DIR pin

# Setup the pins as outputs with default initial states:
# For an A4988, ENABLE is typically active low. Setting it HIGH disables the driver.
GPIO.setup(ENABLE_PIN, GPIO.OUT, initial=GPIO.HIGH)
GPIO.setup(STEP_PIN, GPIO.OUT, initial=GPIO.LOW)
GPIO.setup(DIR_PIN, GPIO.OUT, initial=GPIO.LOW)

# Optionally, add a small delay to ensure the pins settle
time.sleep(1)

# (Don't call GPIO.cleanup() here, since that would reset the pins to input mode,
#  and we want them to remain set at boot.)

print("GPIO initialization complete.")
