/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author Никита
 */
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.Set;
import javax.management.ObjectName;
import javax.management.MBeanServerConnection;

import javax.management.*;
import java.util.ArrayList;
import java.util.List;

public class TomcatPoolMonitor implements Runnable 
{
    private final String dataSourceName; 
    private volatile boolean running = true;
    private final List<Integer> activeHistory = new ArrayList<>();
    private int maxObservedActive = 0; // В конце теста будет хранить самое максимальное значение numActive за все время теста! и оно не должно превыстить текущее значение MaxTotal

    public TomcatPoolMonitor(String dataSourceName) 
    {
        this.dataSourceName = dataSourceName;
        this.maxObservedActive = -1;
    }

    public void stop() 
    {
        this.running = false;
    }

    // Этот поток крутится в цикле и через определенный таймаут читает значение numActive и numIdle и сохраняет в activeHistory
    @Override
    public void run() 
    {
        JMXConnector jmxc = null;
        try 
        {
            // 1. Указываем адрес JMX (соответствует настройкам в setenv.bat)
            JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:9000/jmxrmi");

            // 2. Цикл ожидания открытия порта (Tomcat может загружаться несколько секунд)
            while (running && jmxc == null) 
            {
                try 
                {
                    jmxc = JMXConnectorFactory.connect(url, null);
                } 
                catch (Exception e) 
                {
                    System.out.println(">>> [MONITOR MBEAN] Waiting for Tomcat JMX port 9000...");
                    Thread.sleep(1000); 
                }
            }

            if (!running) return; // Если вызван метод остановки stop то нужно завершать поток.

            // 3. Устанавливаем соединение
            MBeanServerConnection server = jmxc.getMBeanServerConnection();
            // Маска для поиска всех DataSource в любом контексте
            ObjectName queryAll = new ObjectName("*:type=DataSource,*");

            System.out.println(">>> [MONITOR MBEAN] Connected to JMX. Starting scan...");

            // 4. Основной цикл мониторинга
            while (running) 
            {
                try 
                {
                    Set<ObjectName> allDataSources = server.queryNames(queryAll, null);

                    if (allDataSources.isEmpty()) 
                    {
                        // Это может быть нормально до первого обращения к БД
                        System.out.println(">>> [MONITOR BEANS] No DataSources found yet...");
                    } 
                    else 
                    {
                        int totalActive = 0;
                        boolean foundAtLeastOne = false;

                        for (ObjectName name : allDataSources) 
                        {
                            try 
                            {
                                Object attr = server.getAttribute(name, "numActive");
                                if (attr != null) 
                                {
                                    int active = (int) attr;
                                    totalActive += active;
                                    foundAtLeastOne = true;
                                
                                    // Если есть активность, выведем имя пула для диагностики
                                    if (active > 0) 
                                    {
                                        System.out.println(">>> [MONITOR MBEAN] Active connections [" + active + "] in: " + name);
                                    }
                                }
                            } 
                            catch (Exception e) 
                            {
                                // Атрибут numActive может быть недоступен у некоторых MBean
                            }
                        }

                        if (foundAtLeastOne) 
                        {
                            synchronized (this) 
                            {
                                // Как только нашли хоть один пул, уходим от значения -1
                                if (maxObservedActive == -1) maxObservedActive = 0;
                                if (totalActive > maxObservedActive) 
                                {
                                    maxObservedActive = totalActive;
                                }
                            }
                        }
                    }
                } 
                catch (Exception e) 
                {
                    System.err.println(">>> [MONITOR MBEAN] Scan error: " + e.getMessage());
                }
                Thread.sleep(200); // Опрос 5 раз в секунду
            }
        } 
        catch (Exception e) 
        {
            System.err.println(">>> [MONITOR MBEAN] Fatal error in thread: " + e.getMessage());
            e.printStackTrace();
        } 
        finally 
        {
            if (jmxc != null) 
            {
                try { jmxc.close(); } catch (Exception ignored) {}
            }
        System.out.println(">>> [MONITOR MBEAN] Thread stopped.");
        }
    } // End of method run

    public int getMaxObservedActive() 
    {
        return maxObservedActive;
    }

} // End of class.


