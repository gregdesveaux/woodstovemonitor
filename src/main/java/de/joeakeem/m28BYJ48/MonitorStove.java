package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;

/**
 * Coordinates the wood stove monitor hardware and web interface.
 */
public class MonitorStove {
    private static final Logger logger = LoggerFactory.getLogger(MonitorStove.class);
    private static final int DAMPER_FULLY_OPEN = 3000;
    private static final int DIRECTION_CLOSE = 1;
    private static final int DIRECTION_OPEN = 0;
    private static final int START_HIGH_TEMP = 200;
    private static final Path DAMPER_POSITION_FILE = Path.of(
            System.getProperty("user.home"),
            "damper_position.txt"
    );

    private final Temperature temperature;
    private final StepperMotor28BYJ48 stepperMotor;
    private final FileOutputStream tempFile;
    private final Memo memo;

    private record DamperState(int position, boolean inBurnLoop) {
    }

    private int damperPosition = DAMPER_FULLY_OPEN;
    private int temp = 0;
    private int highTemp = START_HIGH_TEMP;
    private boolean inBurnLoop = false;
    private boolean fanOn = false;
    private String status = "Monitoring";
    private boolean fireout = true;

    // Hysteresis band: no movement while inside [TARGET - BAND, TARGET + BAND]
    private static final int BAND_C = 5;

    // Keep some air while burning to avoid smolder/smoke (tune this for your stove)
    private static final int MIN_BURN_OPEN = 0;  // try 600–1200

    // Safety: if truly too hot, you can go below MIN_BURN_OPEN
    private static final int OVERHEAT_C = 270;
    private static final int FIRE_OUT_TEMP_C = 180;
    private static final int FIRE_OUT_OPEN_THRESHOLD = 2000;
    private static final double COOLING_SLOPE_C_PER_MIN = -0.3; // tune: -0.2 to -1.0
    private static final int COAL_PRESERVE_POSITION = 0;        // or 200–400 if you want a tiny crack
    private static final int MIN_STEP = 80;
    private static final int MAX_STEP = 250;
    private static final long ADJUSTMENT_COOLDOWN_MS = 120_000;
    private static final int REVERSAL_ERROR_C = 20;

    private int lastAdjustmentDirection = 0;
    private long lastAdjustmentTs = 0L;


    public static void main(String[] args) {

        new MonitorStove();

    }

    public MonitorStove() {
        memo = new Memo();
        System.setProperty("spark.logging.quiet", "true");
        File f = new File("/home/gdesveau/timeVStemp.csv");
        try {
            tempFile = new FileOutputStream(f, true);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        Context pi4j = Pi4J.newAutoContext();
        stepperMotor = new StepperMotor28BYJ48(pi4j);

        long debounce = 3000;
        DigitalInput button = pi4j.create(
                DigitalInput.newConfigBuilder(pi4j)
                        .id("button")
                        .name("Push Button")
                        .address(17) // GPIO 17
                        .pull(PullResistance.PULL_UP) // Enable internal pull-up resistor
                        .debounce(debounce) // Debounce to prevent false triggers
                        .provider("pigpio-digital-input")
                        .build()
        );
        button.addListener(event -> {
            if (event.state() == DigitalState.LOW) {
                logger.info("Button Pressed!");
                resetBurn();
            } else {
                logger.info("Button Released!");
            }
        });
        DamperState damperState = loadDamperState();
        damperPosition = damperState.position();
        inBurnLoop = damperState.inBurnLoop();
        persistDamperState();
        //stepperMotor.performDemo(rotations);
        temperature = new Temperature();
        new WebInterface(this);

        temp = temperature.getTemp();
        status = inBurnLoop ? "IN BURN LOOP" : "NOT IN BURN LOOP";
        logger.info("Temp: {}", temp);
        //logger.info("Moving damper to open");
        //stepperMotor.moveDamper(damperPosition, DIRECTION_OPEN);
        controlThread();

    }

    int getTemp() {
        return temperature.getTemp();
    }

    int getDamperPosition() {
        return damperPosition;
    }

    int getHighTemp() {
        return highTemp;
    }

    String getStatus() {
        return status;
    }

    private void setStatus(String status) {
        this.status = status;
        logger.info("Status updated: {}", status);
    }

    void setHighTemp(int highTemp) {
        this.highTemp = highTemp;
        logger.info("Updated high temperature threshold to {}", highTemp);
    }

    private void adjustDamperPosition(int delta) {
        setDamperPosition(damperPosition + delta);
    }

    private void setDamperPosition(int position) {
        damperPosition = clampDamperPosition(position);
        persistDamperState();
    }

    private void setInBurnLoop(boolean inBurnLoop) {
        this.inBurnLoop = inBurnLoop;
        persistDamperState();

    }

    private int clampDamperPosition(int position) {
        return Math.max(0, Math.min(DAMPER_FULLY_OPEN, position));
    }

    private void persistDamperState() {
        try {
            Files.writeString(
                    DAMPER_POSITION_FILE,
                    damperPosition + System.lineSeparator() + inBurnLoop,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            logger.error("Failed to persist damper state", e);
        }
    }

    private DamperState loadDamperState() {
        if (!Files.exists(DAMPER_POSITION_FILE)) {
            return new DamperState(DAMPER_FULLY_OPEN, false);
        }

        try {
            var lines = Files.readAllLines(DAMPER_POSITION_FILE, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return new DamperState(DAMPER_FULLY_OPEN, false);
            }
            int position = clampDamperPosition(Integer.parseInt(lines.get(0).trim()));
            boolean storedInBurnLoop = lines.size() > 1 && Boolean.parseBoolean(lines.get(1).trim());
            return new DamperState(position, storedInBurnLoop);
        } catch (IOException | NumberFormatException e) {
            logger.error("Failed to read damper state from file, using default", e);
            return new DamperState(DAMPER_FULLY_OPEN, false);
        }
    }

    void resetBurn() {
        setInBurnLoop(false);
        fireout = false;
        logger.info("Moving damper to open");
        stepperMotor.moveDamper(DAMPER_FULLY_OPEN - damperPosition, DIRECTION_OPEN);
        setDamperPosition(DAMPER_FULLY_OPEN);
        setStatus("Burn reset - NOT IN BURN LOOP");
    }

    void openDamper() {
        int steps = 300;
        if (damperPosition < DAMPER_FULLY_OPEN-steps) {
            stepperMotor.moveDamper(steps, DIRECTION_OPEN);
            setDamperPosition(damperPosition + steps);
            logger.info("moving damper to: {}", damperPosition);
        }
    }

    void closeDamper() {
        int steps = damperPosition;
        logger.info("closing damper");
        stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
        logger.info("damper closed");
        setDamperPosition(0);
        setStatus("Damper closed manually");
    }

    void controlThread() {
        Thread t = new Thread(() -> {
            Ewma tempFilter = new Ewma(0.25);     // tune 0.15–0.35
            Ewma slopeFilter = new Ewma(0.30);

            int lastTemp = temperature.getTemp();
            long lastTs = System.currentTimeMillis();

            while (true) {
                int raw = temperature.getTemp();
                long now = System.currentTimeMillis();

                double dtMin = Math.max(0.25, (now - lastTs) / 60000.0); // minutes
                double slope = (raw - lastTemp) / dtMin;                // °C per minute

                double temp = tempFilter.update(raw);
                double dTdt = slopeFilter.update(slope);

                // Decide whether we're “burning” (so MIN_BURN_OPEN applies)
                // This is intentionally simple + stable.
                boolean burningNow = inBurnLoop || temp >= 205;

                // --- SAFETY OVERRIDE (too hot) ---
                if (raw >= OVERHEAT_C) {
                    int steps = damperPosition; // close hard, but bounded
                    if (steps > 0) {
                        stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                        setDamperPosition(damperPosition - steps);
                        setStatus("OVERHEAT - closing damper");
                    }
                    setInBurnLoop(true);
                    sleepQuietly(130_000);
                    lastTemp = raw;
                    lastTs = now;
                    continue;
                }
// --- COAL PRESERVE OVERRIDE (fire is out) ---
// If we're wide open and cooling and below 180C, close to preserve coals.
                if (damperPosition > FIRE_OUT_OPEN_THRESHOLD
                        && raw <= FIRE_OUT_TEMP_C
                        && dTdt <= COOLING_SLOPE_C_PER_MIN
                        && inBurnLoop) {

                    int targetPos = COAL_PRESERVE_POSITION;

                    // Close in chunks so you don't hammer the mechanism.
                    int delta = damperPosition - targetPos;
                    int steps = delta; // "close hard" but bounded per cycle

                    if (steps > 0) {
                        stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                        setDamperPosition(damperPosition - steps);
                        setStatus("Fire out - closing damper to preserve coals");
                    }

                    // This is not "burning" anymore.
                    setInBurnLoop(false);
                    fireout = true;

                    sleepQuietly(120_000);
                    lastTemp = raw;
                    lastTs = now;
                    continue;
                }
                // Target can still be adjustable from UI if you want:
                int target = highTemp; // set highTemp to 220 from UI, or just use TARGET_TEMP_C
                int low = target - BAND_C;
                int high = target + BAND_C;

                int minOpen = burningNow ? MIN_BURN_OPEN : 0;

                // Predictive nudge: if rising fast, start closing a bit early
                // (prevents overshoot)
                if (dTdt > 3.0) { // >3°C/min is a “getting spicy” slope for many stoves
                    high -= 2;
                }

                int error = (int) Math.round(temp) - target;
                logger.info("temp={} (raw={}), target={}, error={}, slope={:.2f}, high={}, low={}, inBurnLoop={}",
                        Math.round(temp), raw, target, error, dTdt, high, low, inBurnLoop);
                if (temp > high) {

                    // Too hot -> close proportionally
                    int steps = computeStepsClose(error);
                    int newPos = Math.max(minOpen, damperPosition - steps);
                    int delta = damperPosition - newPos;
                    if (delta > 0 && canAdjust(-1, error, now)) {
                        stepperMotor.moveDamper(delta, DIRECTION_CLOSE);
                        setDamperPosition(newPos);
                        setInBurnLoop(true);
                        markAdjustment(-1, now);
                        setStatus("Above target - closing damper");
                    } else if (delta > 0) {
                        setStatus("Holding to avoid oscillation");
                    }
                } else if (temp < low && inBurnLoop) {
                    // Too cool -> open proportionally (but don't exceed fully open)
                    int steps = computeStepsOpen(-error);
                    int newPos = Math.min(DAMPER_FULLY_OPEN, damperPosition + steps);
                    int delta = newPos - damperPosition;
                    if (delta > 0 && canAdjust(1, -error, now)) {
                        stepperMotor.moveDamper(delta, DIRECTION_OPEN);
                        setDamperPosition(newPos);
                        setInBurnLoop(true);
                        markAdjustment(1, now);
                        setStatus("Below target - opening damper");
                    } else if (delta > 0) {
                        setStatus("Holding to avoid oscillation");
                    }
                } else if (!inBurnLoop) {
                    // Inside the band: do nothing (this is the magic that stops flapping)
                    if (fireout) {
                        setStatus("Fire is out, damper is closed to preserve coals");
                    } else {
                        setStatus("not in burn loop");
                    }
                } else {
                    // Inside the band: do nothing (this is the magic that stops flapping)
                    setStatus("Holding steady near target");
                }


                lastTemp = raw;
                lastTs = now;
                long sleepMs = dTdt > 0 ? 45_000 : 120_000;
                sleepQuietly(sleepMs);
            }
        });

        t.setName("DamperController");
        t.setDaemon(true);
        t.start();
    }

    private static int computeStepsClose(int errorC) {
        // errorC is how many °C above target you are (positive)
        int steps = (int) Math.round(errorC * 8.0);
        return clampSteps(steps);
    }

    private static int computeStepsOpen(int belowC) {
        // belowC is how many °C below target you are (positive)
        int steps = (int) Math.round(belowC * 8.0);
        return clampSteps(steps);
    }

    private static int clampSteps(int steps) {
        return Math.max(MIN_STEP, Math.min(MAX_STEP, steps));
    }

    private boolean canAdjust(int direction, int errorC, long now) {
        if (lastAdjustmentDirection == 0 || lastAdjustmentDirection == direction) {
            return true;
        }
        if (now - lastAdjustmentTs >= ADJUSTMENT_COOLDOWN_MS) {
            return true;
        }
        return errorC >= REVERSAL_ERROR_C;
    }

    private void markAdjustment(int direction, long now) {
        lastAdjustmentDirection = direction;
        lastAdjustmentTs = now;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Ewma {
        private final double alpha;
        private Double v;

        private Ewma(double alpha) {
            this.alpha = alpha;
        }

        double update(double x) {
            v = (v == null) ? x : (alpha * x + (1 - alpha) * v);
            return v;
        }
    }

}
