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
    private static  int DAMPER_FULLY_OPEN = 6000;
    private static final int DIRECTION_CLOSE = 1;
    private static final int DIRECTION_OPEN = 0;
    private static final int START_HIGH_TEMP = 240;
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
    private static final int BAND_C = 3;

    // Keep some air while burning to avoid smolder/smoke (tune this for your stove)
    private static final int MIN_BURN_OPEN = 450;
    private static final int RECOVERY_MIN_BURN_OPEN = 1050;
    private static final double RECOVERY_COOLING_SLOPE_C_PER_MIN = -2.5;
    private static final int RECOVERY_ABOVE_TARGET_C = 6;

    // Safety: if truly too hot, you can go below MIN_BURN_OPEN
    private static final int OVERHEAT_C = 280;
    private static final int APPROACH_HIGH_C = 20;
    private static final double APPROACH_SLOPE_C_PER_MIN = 0.15;
    private static final int FIRE_OUT_TEMP_C = 140;
    private static final int FIRE_OUT_OPEN_THRESHOLD = 3000;
    private static final int NOT_IN_BURN_LOOP_OPEN_THRESHOLD = 200;
    private static final int NOT_IN_BURN_LOOP_TARGET_POSITION = 3000;
    private static final double COOLING_SLOPE_C_PER_MIN = -0.3; // tune: -0.2 to -1.0
    private static final int COAL_PRESERVE_POSITION = 0;        // or 200–400 if you want a tiny crack
    private static final int MIN_STEP = 300;
    private static final int MAX_STEP = 600;
    private static final int MIN_STEP_NEAR = 150;
    private static final int MAX_STEP_NEAR = 450;
    private static final int NEAR_TARGET_ERROR_C = 10;
    private static final long ADJUSTMENT_COOLDOWN_MS = 120_000;
    private static final int REVERSAL_ERROR_C = 20;
    private static final long ACTIVE_MONITOR_SLEEP_MS = 30_000;
    private static final long PASSIVE_MONITOR_SLEEP_MS = 120_000;

    private int lastAdjustmentDirection = 0;
    private long lastAdjustmentTs = 0L;
    private boolean overheatRecoveryNeeded = false;
    long watchdogTime = 0;

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
        watchdog();
        temp = temperature.getTemp();
        status = inBurnLoop ? "IN BURN LOOP" : "NOT IN BURN LOOP";

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
        DAMPER_FULLY_OPEN=6000; // open damper fully to start fire, then control loop will adjust as needed
        logger.info(
                "Reset burn requested (damperPosition={}, inBurnLoop={}, temp={})",
                damperPosition,
                inBurnLoop,
                temperature.getTemp()
        );
        logger.info("Moving damper to open");
        stepperMotor.moveDamper(DAMPER_FULLY_OPEN - damperPosition, DIRECTION_OPEN);
        setDamperPosition(DAMPER_FULLY_OPEN);
        setStatus("Burn reset - NOT IN BURN LOOP");
    }

    void openDamper() {
        int steps = 300;
        if (damperPosition < DAMPER_FULLY_OPEN - steps) {
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

    void calibrateDamper() {
        logger.info("Calibrating damper position");
        if (damperPosition > 0) {
            stepperMotor.moveDamper(damperPosition, DIRECTION_CLOSE);
        }
        stepperMotor.moveDamper(100, DIRECTION_CLOSE);
        setDamperPosition(0);
        setStatus("Damper calibrated");
    }

    void controlThread() {
        Thread t = new Thread(() -> {
            Ewma tempFilter = new Ewma(0.25);     // tune 0.15–0.35
            Ewma slopeFilter = new Ewma(0.30);

            int lastTemp = temperature.getTemp();
            long lastTs = System.currentTimeMillis();

            while (true) {
                try {
                    watchdogTime = System.currentTimeMillis();
                    int raw = temperature.getTemp();
                    if (raw == -273) {
                        raw = lastTemp;
                    } // handle sensor read failure by treating as no change
                    long now = System.currentTimeMillis();
                    int roomTemp = temperature.getRoomTemp();
                    if (roomTemp > 50 && !fanOn) {
                        memo.setOn();
                        fanOn = true;
                    } else if (roomTemp < 50 && roomTemp > 0 && fanOn) {
                        memo.setOff();
                        fanOn = false;
                    }

                    double dtMin = Math.max(0.25, (now - lastTs) / 60000.0); // minutes
                    double slope = (raw - lastTemp) / dtMin;                // °C per minute

                    double temp = tempFilter.update(raw);
                    double dTdt = slopeFilter.update(slope);

                    // Target can still be adjustable from UI if you want:
                    int target = highTemp; // set highTemp to 220 from UI, or just use TARGET_TEMP_C

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
                        overheatRecoveryNeeded = true;
                        sleepQuietly(130_000);
                        lastTemp = raw;
                        lastTs = now;
                        continue;
                    }
                    if (overheatRecoveryNeeded && raw <= OVERHEAT_C - 20) {
                        int targetPos = 900;
                        int delta = targetPos - damperPosition;
                        if (delta > 0) {
                            stepperMotor.moveDamper(delta, DIRECTION_OPEN);
                            setDamperPosition(damperPosition + delta);
                            setStatus("Recovered from overheat - opening damper to minimum burn");
                        }
                        overheatRecoveryNeeded = false;
                    }
                    if (!inBurnLoop && raw > NOT_IN_BURN_LOOP_OPEN_THRESHOLD) {
                        int targetPos = NOT_IN_BURN_LOOP_TARGET_POSITION;
                        int delta = targetPos - damperPosition;
                        if (delta != 0) {
                            int direction = delta > 0 ? DIRECTION_OPEN : DIRECTION_CLOSE;
                            stepperMotor.moveDamper(Math.abs(delta), direction);
                            setDamperPosition(targetPos);
                            setStatus("Temp above 200 while not in burn loop - setting damper to 3000");

                        lastTemp = raw;
                        lastTs = now;
                            DAMPER_FULLY_OPEN=NOT_IN_BURN_LOOP_TARGET_POSITION; // once above 200 we only need between 0 and 3000, so treat 3000 as fully open for the rest of this burn
                        sleepQuietly(monitoringSleepMs(raw, target, dTdt));
                        continue;}
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
                    int low = target - BAND_C;
                    int high = target + BAND_C;

                    int minOpen = burningNow ? MIN_BURN_OPEN : 0;

                    // Predictive nudge: if rising fast, start closing a bit early
                    // (prevents overshoot)
                    if (dTdt > 3.0) { // >3°C/min is a “getting spicy” slope for many stoves
                        high -= 2;
                    }

                    int error = (int) Math.round(temp) - target;
                    logger.info("temp={} (raw={}), target={}, error={}, slope={}, high={}, low={}, inBurnLoop={}, damper={}",
                            Math.round(temp), raw, target, error, dTdt, high, low, inBurnLoop,damperPosition);
                    boolean risingTowardHigh = dTdt >= APPROACH_SLOPE_C_PER_MIN
                            && temp >= (target - APPROACH_HIGH_C)
                            && temp <= high;
                    boolean fallingTowardHigh = dTdt <= -APPROACH_SLOPE_C_PER_MIN
                            && temp <= (target + APPROACH_HIGH_C)
                            && temp >= low;

                    boolean coolingTooFastNearPeak = inBurnLoop
                            && damperPosition <= MIN_BURN_OPEN
                            && temp >= target + RECOVERY_ABOVE_TARGET_C
                            && dTdt <= RECOVERY_COOLING_SLOPE_C_PER_MIN;

                    if (coolingTooFastNearPeak) {
                        int newPos = Math.min(DAMPER_FULLY_OPEN, Math.max(damperPosition, RECOVERY_MIN_BURN_OPEN));
                        int delta = newPos - damperPosition;
                        if (delta > 0 && canAdjust(1, (int) Math.round(temp) - target, now)) {
                            stepperMotor.moveDamper(delta, DIRECTION_OPEN);
                            setDamperPosition(newPos);
                            setInBurnLoop(true);
                            markAdjustment(1, now);
                            setStatus("Cooling too fast above target - opening damper to protect flame");
                        }
                    } else if (temp > high) {

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
                        } else{
                            logger.info("not moving damper delta{} ",delta);
                        }
                    } else if (risingTowardHigh) {
                        int approachError = (int) Math.round(temp) - (target - APPROACH_HIGH_C);
                        int steps = computeStepsClose(Math.max(0, approachError));
                        int newPos = Math.max(minOpen, damperPosition - steps);
                        int delta = damperPosition - newPos;
                        if (delta > 0 && canAdjust(-1, approachError, now)) {
                            stepperMotor.moveDamper(delta, DIRECTION_CLOSE);
                            setDamperPosition(newPos);
                            setInBurnLoop(true);
                            markAdjustment(-1, now);
                            setStatus("Approaching target - closing damper");
                        } else if (delta > 0) {
                            setStatus("Holding to avoid oscillation");
                        }
                    } else if (fallingTowardHigh && inBurnLoop) {
                        int approachError = (target + APPROACH_HIGH_C) - (int) Math.round(temp);
                        int steps = computeStepsOpen(Math.max(0, approachError));
                        int newPos = Math.min(DAMPER_FULLY_OPEN, damperPosition + steps);
                        int delta = newPos - damperPosition;
                        if (delta > 0 && canAdjust(1, approachError, now)) {
                            stepperMotor.moveDamper(delta, DIRECTION_OPEN);
                            setDamperPosition(newPos);
                            setInBurnLoop(true);
                            markAdjustment(1, now);
                            setStatus("Cooling near target - opening damper");
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
                    sleepQuietly(monitoringSleepMs(raw, target, dTdt));
                } catch (Exception e) {
                    logger.error("Control loop error; continuing after delay", e);
                    lastTemp = temperature.getTemp();
                    lastTs = System.currentTimeMillis();
                    sleepQuietly(ACTIVE_MONITOR_SLEEP_MS);
                }
            }
        });

        t.setName("DamperController");
        t.setDaemon(true);
        t.start();
    }

    private static int computeStepsClose(int errorC) {
        // errorC is how many °C above target you are (positive)
        int steps = (int) Math.round(errorC * 8.0);
        return clampSteps(steps, errorC);
    }

    private static int computeStepsOpen(int belowC) {
        // belowC is how many °C below target you are (positive)
        int steps = (int) Math.round(belowC * 8.0);
        return clampSteps(steps, belowC);
    }

    private static int clampSteps(int steps, int errorC) {
        int minStep = errorC <= NEAR_TARGET_ERROR_C ? MIN_STEP_NEAR : MIN_STEP;
        int maxStep = errorC <= NEAR_TARGET_ERROR_C ? MAX_STEP_NEAR : MAX_STEP;
        return Math.max(minStep, Math.min(maxStep, steps));
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

    private static long monitoringSleepMs(int raw, int target, double slopeCPerMin) {
        long sleepMs = slopeCPerMin > 0 ? ACTIVE_MONITOR_SLEEP_MS : PASSIVE_MONITOR_SLEEP_MS;
        int dangerThreshold = Math.max(NOT_IN_BURN_LOOP_OPEN_THRESHOLD, target - APPROACH_HIGH_C);
        if (raw >= dangerThreshold) {
            return Math.min(sleepMs, ACTIVE_MONITOR_SLEEP_MS);
        }
        return sleepMs;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void watchdog() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                long now = System.currentTimeMillis();
                if (now - watchdogTime > 240_000) {
                    logger.error("Watchdog detected stall");
                    logger.error("stack dump ************************************\n");
                    logger.error(dumpStacksSimple());
                    logger.error("end stack dump ************************************\n");

                }
            }
        });
        logger.info("Starting watchdog thread");
        t.setName("Watchdog");
        t.setDaemon(true);
        t.start();
    }

    public String dumpStacksSimple() {
        StringBuilder sb = new StringBuilder();
        for (var e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            sb.append('"').append(t.getName()).append("\" ")
                    .append("Id=").append(t.getId()).append(" ")
                    .append(t.getState()).append('\n');
            for (StackTraceElement ste : e.getValue()) {
                sb.append("    at ").append(ste).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
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
