package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.i2c.I2C;
import com.pi4j.io.i2c.I2CConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Temperature {

    private static final Logger logger = LoggerFactory.getLogger(Temperature.class);
    private static final long I2C_READ_TIMEOUT_MS = 500;
    private final I2C i2c;
    private final ExecutorService i2cExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "i2c-read"));

    public Temperature() {
        Context pi4j = Pi4J.newAutoContext();

        try {
            I2CConfig i2cConfig = I2C.newConfigBuilder(pi4j)
                    .id("mlx90614")
                    .name("MLX90614 Sensor")
                    .bus(1)
                    .device(0x5A)
                    .build();

            i2c = pi4j.create(i2cConfig);
        } catch (Exception e) {
            throw new RuntimeException("Unable to initialize temperature sensor", e);
        }
    }

    int getTemp() {
        int objectTempRaw;
        try {
            objectTempRaw = readTemperatureRegisterWithTimeout(0x07);
            logger.debug("Sensor temp: {}", (int) Math.round(convertToCelsius(readTemperatureRegisterWithTimeout(0x06))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return (int) Math.round(convertToCelsius(objectTempRaw));
    }

    int getRoomTemp() {
        int objectTempRaw;
        try {
            objectTempRaw = readTemperatureRegisterWithTimeout(0x06);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return (int) Math.round(convertToCelsius(objectTempRaw));
    }

    private static int readTemperatureRegister(I2C i2c, int register) throws Exception {
        byte[] buffer = new byte[2];
        i2c.readRegister(register, buffer, 0, 2);

        int rawValue = (buffer[1] & 0xFF) << 8 | (buffer[0] & 0xFF);
        return rawValue;
    }

    private int readTemperatureRegisterWithTimeout(int register) throws Exception {
        Future<Integer> future = i2cExecutor.submit(() -> readTemperatureRegister(i2c, register));
        try {
            return future.get(I2C_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("Timed out after " + I2C_READ_TIMEOUT_MS + "ms reading register 0x"
                    + Integer.toHexString(register));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private static double convertToCelsius(int rawData) {
        return (rawData * 0.02) - 273.15;
    }
}
