/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author Никита
 */
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DBConnectionMonitor implements Runnable 
{
    private final String url;
    private final String user;
    private final String password;
    private volatile boolean running = true;
    private int maxObservedFromDB = -1;
    private final List<Integer> history = new ArrayList<>();

    public DBConnectionMonitor(String url, String user, String password) 
    {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public void stop() 
    {
        this.running = false;
    }

    @Override
    public void run() 
    {
        // 1. Устанавливаем соединение монитора
        try (Connection conn = DriverManager.getConnection(url, user, password)) {

            // Прямой SQL запрос без знаков вопроса
            String sql = "SELECT count(*) FROM pg_stat_activity WHERE datname = 'my_test_db'";

            while (running) {
                // 2. Используем обычный Statement вместо PreparedStatement
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {

                    if (rs.next()) {
                        int currentCount = rs.getInt(1); // Берем результат count(*)
                        System.out.println(">>> [DB MONITOR] Current sessions in DB: " + currentCount);
                        synchronized (this) {
                            if (maxObservedFromDB == -1) maxObservedFromDB = 0;
                            if (currentCount > maxObservedFromDB) {
                                maxObservedFromDB = currentCount;
                            }
                        }
                    }
                } catch (SQLException e) {
                    // Если база данных на мгновение стала недоступна
                    System.err.println(">>> [DB MONITOR] Query error: " + e.getMessage());
                }

                Thread.sleep(500); // Пауза между опросами
            }
        } catch (Exception e) {
            System.err.println(">>> [DB MONITOR] Connection error: " + e.getMessage());
        }
    }

    public synchronized int getMaxObservedFromDB() 
    {
        return maxObservedFromDB;
    }
}