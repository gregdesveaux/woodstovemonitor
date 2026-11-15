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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.Properties;

/**
 * Coordinates the wood stove monitor hardware and web interface.
 */
public class MonitorStove {
    private static final Logger logger = LoggerFactory.getLogger(MonitorStove.class);
    private static final String VERSION = loadVersion();
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
    private  FileOutputStream tempFile=null;
    private final Memo memo;

    private record DamperState(int position, boolean mapControlActive) {
    }

    private int damperPosition = DAMPER_FULLY_OPEN;
    private int temp = 0;
    private int highTemp = START_HIGH_TEMP;
    private boolean mapControlActive = false;
    private boolean fanOn = false;
    private String status = "Monitoring";

    private static final int DAMPER_MOVE_DEADBAND = 100;
    private static final int APPROACH_HIGH_C = 20;
    private static final int FIRE_OUT_TEMP_C = 140;
    private static final int FIRE_OUT_OPEN_THRESHOLD = 3000;
    private static final int MAP_CONTROL_ACTIVATION_TEMP_C = 200;
    private static final double COOLING_SLOPE_C_PER_MIN = -0.3; // tune: -0.2 to -1.0
    private static final int COAL_PRESERVE_POSITION = 0;        // or 200–400 if you want a tiny crack
    private static final long ACTIVE_MONITOR_SLEEP_MS = 90_000;
    private static final long PASSIVE_MONITOR_SLEEP_MS = 120_000;

    long watchdogTime = 0;

    public static void main(String[] args) {
        logger.info("Starting Wood Stove Monitor {}", VERSION);
        new MonitorStove();

    }

    private static String loadVersion() {
        Properties props = new Properties();
        try (InputStream in = MonitorStove.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            return "unknown";
        }
        String version = props.getProperty("version", "unknown");
        String buildNumber = props.getProperty("build.number", "unknown");
        String buildTime = props.getProperty("build.time", "unknown");
        if (version.contains("${") || buildNumber.contains("${")) {
            return "dev";
        }
        return version + " build " + buildNumber + " (" + buildTime + ")";
    }

    public MonitorStove() {
        memo = new Memo();
        System.setProperty("spark.logging.quiet", "true");
        File f = new File("/home/gdesveau/timeVStemp.csv");
        try {
            tempFile = new FileOutputStream(f, true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
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
        mapControlActive = damperState.mapControlActive();
        persistDamperState();
        //stepperMotor.performDemo(rotations);
        temperature = new Temperature();
        new WebInterface(this);
        watchdog();
        temp = temperature.getTemp();
        status = mapControlActive ? "MAP CONTROL ACTIVE" : "MAP CONTROL INACTIVE";

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
        try {
            String csvLine = new Date() + "," + temperature.getTemp() + "," + damperPosition + System.lineSeparator();
            tempFile.write(csvLine.getBytes(StandardCharsets.UTF_8));
            tempFile.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        persistDamperState();
    }

    private void setMapControlActive(boolean mapControlActive) {
        this.mapControlActive = mapControlActive;
        persistDamperState();
    }

    private int clampDamperPosition(int position) {
        return Math.max(0, Math.min(DAMPER_FULLY_OPEN, position));
    }

    private void persistDamperState() {
        try {
            Files.writeString(
                    DAMPER_POSITION_FILE,
                    damperPosition + System.lineSeparator() + mapControlActive,
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
            boolean storedMapControlActive = lines.size() > 1 && Boolean.parseBoolean(lines.get(1).trim());
            return new DamperState(position, storedMapControlActive);
        } catch (IOException | NumberFormatException e) {
            logger.error("Failed to read damper state from file, using default", e);
            return new DamperState(DAMPER_FULLY_OPEN, false);
        }
    }

    void resetBurn() {
        setMapControlActive(false);
        DAMPER_FULLY_OPEN=6000; // open damper fully to start fire, then control loop will adjust as needed
        logger.info(
                "Reset burn requested (damperPosition={}, mapControlActive={}, temp={})",
                damperPosition,
                mapControlActive,
                temperature.getTemp()
        );
        logger.info("Moving damper to open");
        stepperMotor.moveDamper(DAMPER_FULLY_OPEN - damperPosition, DIRECTION_OPEN);
        setDamperPosition(DAMPER_FULLY_OPEN);
        setStatus("Burn reset - MAP CONTROL INACTIVE");
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

                    int target = highTemp;

                    if (!mapControlActive && raw > MAP_CONTROL_ACTIVATION_TEMP_C) {
                        setMapControlActive(true);
                        logger.info("Temperature map control activated at raw temp {}", raw);
                    }

                    if (mapControlActive) {
                        int targetPos = damperPositionForTemperature(raw);
                        int delta = targetPos - damperPosition;
                        if (Math.abs(delta) > DAMPER_MOVE_DEADBAND) {
                            int direction = delta > 0 ? DIRECTION_OPEN : DIRECTION_CLOSE;
                            stepperMotor.moveDamper(Math.abs(delta), direction);
                            setDamperPosition(targetPos);
                            setStatus("Temperature-map control active temp: " + raw);
                        }
                        lastTemp = raw;
                        lastTs = now;
                        sleepQuietly(monitoringSleepMs(raw, target, slope));
                        continue;
                    }

                    // --- COAL PRESERVE OVERRIDE (fire is out) ---
                    // If we're wide open and cooling and below 180C, close to preserve coals.
                    if (damperPosition > FIRE_OUT_OPEN_THRESHOLD
                            && raw <= FIRE_OUT_TEMP_C
                            && slope <= COOLING_SLOPE_C_PER_MIN) {
                        int steps = damperPosition - COAL_PRESERVE_POSITION;
                        if (steps > 0) {
                            stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                            setDamperPosition(COAL_PRESERVE_POSITION);
                            setStatus("Fire out - closing damper to preserve coals");
                        }
                        lastTemp = raw;
                        lastTs = now;
                        setMapControlActive(false);
                        sleepQuietly(PASSIVE_MONITOR_SLEEP_MS);
                        continue;
                    }

                    logger.info("temp={}, target={}, slope={}, damper={}, waiting for >{}C",
                            raw, target, slope, damperPosition, MAP_CONTROL_ACTIVATION_TEMP_C);
                    lastTemp = raw;
                    lastTs = now;
                    sleepQuietly(monitoringSleepMs(raw, target, slope));
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

    private static long monitoringSleepMs(int raw, int target, double slopeCPerMin) {
        long sleepMs = slopeCPerMin > 0 ? ACTIVE_MONITOR_SLEEP_MS : PASSIVE_MONITOR_SLEEP_MS;
        int dangerThreshold = Math.max(MAP_CONTROL_ACTIVATION_TEMP_C, target - APPROACH_HIGH_C);
        if (raw >= dangerThreshold) {
            return Math.min(sleepMs, ACTIVE_MONITOR_SLEEP_MS);
        }
        return sleepMs;
    }

    private static int damperPositionForTemperature(int temperatureC) {
        if (temperatureC >= 280) {
            return 0;
        }
        if (temperatureC >= 270) {
            return 300;
        }
        if (temperatureC >= 260) {
            return 600;
        }
        if (temperatureC >= 250) {
            return 900;
        }
        if (temperatureC >= 240) {
            return 1000;
        }
        if (temperatureC >= 230) {
            return 1200;
        }
        if (temperatureC >= 220) {
            return 1500;
        }
        if (temperatureC >= 210) {
            return 2000;
        }
        if (temperatureC >= 200) {
            return 2100;
        }
        if (temperatureC >= 190) {
            return 2200;
        }
        if (temperatureC >= 180) {
            return 2400;
        }
        if (temperatureC >= 170) {
            return 2600;
        }
        if (temperatureC >= 160) {
            return 2800;
        }
        if (temperatureC >= 150) {
            return 3000;
        }
        if (temperatureC >= 140) {
            return 3000;
        }
        if (temperatureC >= 130) {
            return 0;
        }
        return 0;
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



}
