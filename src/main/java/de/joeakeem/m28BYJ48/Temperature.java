package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.i2c.I2C;
import com.pi4j.io.i2c.I2CConfig;

public class Temperature {

    I2C i2c;
    public Temperature() {
        // Create a Pi4J context
        Context pi4j = Pi4J.newAutoContext();

        try {
            // Configure the I2C device
            I2CConfig i2cConfig = I2C.newConfigBuilder(pi4j)
                    .id("mlx90614")
                    .name("MLX90614 Sensor")
                    .bus(1)              // I2C bus 1
                    .device(0x5A)        // MLX90614 I2C address
                    .build();

            // Create an I2C instance
            i2c = pi4j.create(i2cConfig);




        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    int getTemp(){
        int objectTempRaw = 0;
        try {
            objectTempRaw = readTemperatureRegister(i2c, 0x07);
            System.out.println("Sensor temp: "+(int)Math.round(convertToCelsius(readTemperatureRegister(i2c, 0x06))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
            int objectTemp = (int)Math.round(convertToCelsius(objectTempRaw));


        return objectTemp;
    }
    int getRoomTemp(){
        int objectTempRaw = 0;
        try {
            objectTempRaw = readTemperatureRegister(i2c, 0x06);
              } catch (Exception e) {
            throw new RuntimeException(e);
        }
        int objectTemp = (int)Math.round(convertToCelsius(objectTempRaw));


        return objectTemp;
    }

    // Helper method to read and swap bytes
    private static int readTemperatureRegister(I2C i2c, int register) throws Exception {
        byte[] buffer = new byte[2];
        i2c.readRegister(register, buffer, 0, 2);

        // Convert little-endian raw data to int
        int rawValue = (buffer[1] & 0xFF) << 8 | (buffer[0] & 0xFF);
        return rawValue;
    }

    // Helper method to convert raw data to Celsius
    private static double convertToCelsius(int rawData) {
        return (rawData * 0.02) - 273.15; // Conversion formula from the MLX90614 datasheet
    }
}