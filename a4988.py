import RPi.GPIO as GPIO
import time

GPIO.setmode(GPIO.BCM)
STEP_PIN = 18
DIR_PIN = 23
ENABLE_PIN = 24

GPIO.setup(STEP_PIN, GPIO.OUT)
GPIO.setup(DIR_PIN, GPIO.OUT)
GPIO.setup(ENABLE_PIN, GPIO.OUT)

# Set the direction
GPIO.output(DIR_PIN, GPIO.HIGH)
GPIO.output(ENABLE_PIN, GPIO.LOW)
print("Driver enabled.")

# Step the motor 200 steps (adjust delay as needed)
for i in range(300):
    GPIO.output(STEP_PIN, GPIO.HIGH)
    time.sleep(0.005)
    GPIO.output(STEP_PIN, GPIO.LOW)
    time.sleep(0.005)

GPIO.output(ENABLE_PIN, GPIO.HIGH)
GPIO.output(STEP_PIN, GPIO.LOW)
GPIO.output(DIR_PIN, GPIO.LOW)
print("Driver disabled.")

GPIO.cleanup()
