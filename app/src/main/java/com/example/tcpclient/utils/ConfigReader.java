package com.example.tcpclient.utils;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigReader {
    private final Properties properties;
    private static final String TAG = "ConfigReader";

    public ConfigReader(Context context) {
        properties = new Properties();
        try {
            InputStream inputStream = context.getAssets().open("server_config.properties");
            properties.load(inputStream);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load server_config.properties", e);
        }
    }

    public String getServerIp() {
        return properties.getProperty("server_ip", "127.0.0.1");
    }

    public int getServerPort() {
        String port = properties.getProperty("server_port", "12345");
        return Integer.parseInt(port);
    }
}
