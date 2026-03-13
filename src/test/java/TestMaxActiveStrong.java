
import io.qameta.allure.Allure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.qameta.allure.Description; 
import java.io.FileNotFoundException;

import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.List;
import java.util.stream.*;
import java.io.File;
import java.io.IOException;
import java.util.Comparator;

// Импорты для метода UpdateContextXml:
import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestMaxActiveStrong 
{
    // Класс для хранения параметров нашего теста: 
    static class TestConfig 
    {
        // Входные параметры для тестов:
        String name;                                //  Имя или цель или описание смысла выполнения теста с этими параметрами.
        int number;                                 //  Порядковый номер теста 
        int maxActive;                              //  Значение MaxActive для текущего элемента скиска параметров. 
        long maxWait;                               //  Значение MaxWait для текущего элемента скиска параметров. Сколько ждать максимально завершения запроса HTTP в сервлет.
        long clientSleep;                           //  Значение Sleep для указания времени которое клиентский поток должен ждать освобождения коннекшена в пуле 
        int threads;                                //  Общее число фич/потоков запускаемых клиентом в текущем выполняемом тесте.
        
        // Ожидаемые результаты для ассертов:
        long expectedOk;                            //  Сколько запросов фич (потоков) дожлно быть завершено успешно в данном тесте. 
        long expectedFast;                          //  Сколько запросов фич (потоков) дожлно быть завершено успешно быстро без ожидания.  
        long expectedDelayed;                       //  Сколько запросов фич (потоков) дожлно быть завершено успешно после ожидания.
        long expectedError;                         //  Сколько запросов фич (потоков) должно быть завершено по таймауту с ошибкой после ожидания истечения таймаута.
        

        // Конструктор класса хранения параметров нагшего теста: 
        public TestConfig(String name, int number, int maxActive, int maxWait, int clientSleep, int threads, long ok, long fast, long delayed,long err) 
        {
            this.name = name;
            this.number = number;
            this.maxActive = maxActive;
            this.maxWait = maxWait;
            this.clientSleep = clientSleep;
            this.threads = threads;
            this.expectedOk = ok;
            this.expectedFast = fast;
            this.expectedDelayed = delayed;
            this.expectedError = err; // Записываем ожидаемые ошибки
        }
        
        // Переопределим метод для удобства: JUnit 5 при отображении в дереве тестов (и Allure в заголовке) часто использует toString() объекта, если не указано иное.
        @Override
        public String toString() 
        {
            return name;
        }

    } // End of class TestConfig
    
    // Реальные значения параметров для наших тестов - если нужно добавить новых тестов просто здесь заполняем ноые строки:
    
    private static final List<TestConfig> SCENARIOS = 
            List.of(
                    //                Имя,                                                                                    Number    MaxActive,       MaxWait,     Sleep,  Threads,    ExpOk,    ExpFast,   ExpDelayed  ExpError
                    new TestConfig("Normal Wait   All: 4  MaxAct:2  OK:4 Fast:2 Delay:2 Error:0 MaxWait:  10000 Sleep:5000",    1,       2,            10000,      5000,     4,         4L,       2L,          2L,       0L   ), 
                    new TestConfig("Timeout Fail  All: 4  MaxAct:2  Ok:2 Fast:2 Delay:0 Error:2 MaxWait:   3000 Sleep:5000",    2,       2,             3000,      5000,     4,         2L,       2L,          0L,       2L   ),
                    new TestConfig("Stairway      All: 4  MaxAct:1  OK:4 Fast:1 Delay:3 Error:0 MaxWait:  30000 Sleep:5000",    3,       1,            30000,      5000,     4,         4L,       1L,          3L,       0L   ),                        
                    new TestConfig("Fast timeout  All: 4  MaxAct:2  OK:2 Fast:2 Delay:0 Error:2 MaxWait:    100 Sleep:5000",    4,       2,              100,      5000,     4,         2L,       2L,          0L,       2L   ),                        
                    new TestConfig("Wide gateway  All: 4  MaxAct:10 OK:4 Fast:4 Delay:0 Error:0 MaxWait: 100000 Sleep:5000",    5,      10,           100000,      5000,     4,         4L,       4L,          0L,       0L   ),
                    new TestConfig("Stairway 10   All:10  MaxAct:2  OK:6 Fast:2 Delay:4 Error:4 MaxWait:  14000 Sleep:5000",    6,       2,            14000,      5000,     10,        6L,       2L,          4L,       4L   ), 
                    new TestConfig("Border 10     All:10  MaxAct:2  OK:6 Fast:2 Delay:4 Error:4 MaxWait:  15000 Sleep:5000",    7,       2,            15000,      5000,     10,        6L,       2L,          4L,       4L   ),
                    new TestConfig("Nothing       All:10  MaxAct:0  OK:0 Fast:10 Delay:0 Error:10 MaxWait: 1000 Sleep:5000",    8,       0,             1000,      5000,     10,        0L,       10L,         0L,       10L  )

            ); 
    
    
    // Класс для хранения результатов теста:
    static class TestResult 
    {
        int code;
        long startOffset;
        long duration;
        String body; 
        
        TestResult(int code, long startOffset, long duration, String body) 
        {
            this.code = code;
            this.startOffset = startOffset;
            this.duration = duration;
            this.body = body;
        }
    }
    
    // Проверяем, есть ли папка tomcat прямо в текущей директории (как в Jenkins) или как в NetBeans)
    // При запуске из среды она внутри TestClient
    private String getTomcatPath() 
    {
        File localTomcat = new File(System.getProperty("user.dir") + File.separator + "tomcat");
        if (localTomcat.exists()) 
        {
            return localTomcat.getAbsolutePath();
        }
        return new File(System.getProperty("user.dir") + File.separator + ".." + File.separator + "tomcat").getAbsolutePath();
    }


    // Константы для запуска и остановки TomCat:
    private static final int TOMCAT_STARTUP_TIMEOUT_MS = 10000;
    private static final int TOMCAT_SHUTDOWN_TIMEOUT_MS = 3000;
    private final String TOMCAT_HOME = getTomcatPath();
    private final String TOMCAT_BIN = TOMCAT_HOME + File.separator + "bin";
    private int tempMaxNumActive  = 0; 
 
    // Отказываемся от этого элемента  @BeforeEach так как нам нужно иметь доступ к параметрами каждого теста.
    // Метод запуска TomCat:
    // Старый вариант:
    //    void startTomcat() throws IOException, InterruptedException 
    //    {
    //        System.out.println("\nRun Tomcat from: " + TOMCAT_BIN);
    //        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "startup.bat");
    //        pb.directory(new File(TOMCAT_BIN));
    //        pb.start();
    //        Thread.sleep(TOMCAT_STARTUP_TIMEOUT_MS);
    //    }
    
    public void startTomcat() throws IOException 
    {
        // Принудительная очистка: 
        if (System.getProperty("os.name").toLowerCase().contains("win")) 
        {
            try 
            {
                System.out.println(">>> [CLEANUP] Force closing Tomcat windows...");
        
                // 1. Убиваем все окна командной строки, в заголовке которых есть "Tomcat"
                // Это самый быстрый способ закрыть зависшие консоли
                Runtime.getRuntime().exec("taskkill /f /fi \"WINDOWTITLE eq Tomcat*\" /t");
        
                // 2. Пауза, чтобы ОС освободила сокеты портов 8080 и 9000
                Thread.sleep(2000); 
        
                System.out.println(">>> [CLEANUP] Done. Starting fresh Tomcat...");
            } 
            catch (Exception e) 
            {
                // Игнорируем, если окон не было
            }
        }

//        Старый вариант с абсолютным путем:
//        File binDir = new File("D:/REPO_JAVA/FinalTest/tomcat/bin");
//        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "startup.bat");
//        pb.directory(binDir);
//
//        // Внедряем параметры JMX прямо в окружение этого конкретного процесса
//        // Используем CATALINA_OPTS — это стандарт для Tomcat
//        String jmxOptions = "-Dcom.sun.management.jmxremote " +
//                            "-Dcom.sun.management.jmxremote.port=9000 " +
//                            "-Dcom.sun.management.jmxremote.rmi.port=9000 " +
//                            "-Dcom.sun.management.jmxremote.authenticate=false " +
//                            "-Dcom.sun.management.jmxremote.ssl=false " +
//                            "-Djava.rmi.server.hostname=localhost";
//    
//        pb.environment().put("CATALINA_OPTS", jmxOptions);
//    
//        pb.start();
//        System.out.println(">>> TomCat started with JMX on port 9000");

        // 1. Получаем папку, где запущен тест (это .../FinalTest/StrongTestPool)
        String currentDir = System.getProperty("user.dir");

        // 2. Поднимаемся на уровень выше в папку FinalTest
        String projectRoot = new File(currentDir).getParent();

        // 3. Строим путь к папке bin Tomcat относительно корня FinalTest
        File binDir = new File(projectRoot, "tomcat" + File.separator + "bin");

        // Печатаем для проверки - путь должен стать D:\REPO_JAVA\FinalTest\tomcat\bin
        System.out.println(">>> [CONFIG] Current User Dir: " + currentDir);
        System.out.println(">>> [CONFIG] Target Tomcat Bin: " + binDir.getAbsolutePath());

        // 4. Проверяем, существует ли папка, чтобы не гадать
        if (!binDir.exists())
        {
              throw new RuntimeException("Critical error: Folder Tomcat is not found in the path: " + binDir.getAbsolutePath());
        }

        // 5. Настраиваем запуск
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "startup.bat");
        pb.directory(binDir); 

        // Внедряем параметры JMX (CATALINA_OPTS)
        String jmxOptions = "-Dcom.sun.management.jmxremote " +
                              "-Dcom.sun.management.jmxremote.port=9000 " +
                              "-Dcom.sun.management.jmxremote.rmi.port=9000 " +
                              "-Dcom.sun.management.jmxremote.authenticate=false " +
                              "-Dcom.sun.management.jmxremote.ssl=false " +
                              "-Djava.rmi.server.hostname=localhost";

        pb.environment().put("CATALINA_OPTS", jmxOptions);

        pb.start();
        System.out.println(">>> TomCat started with JMX on port 9000");

    }    

    // Ну тогда логично отказаться и от этого элемента: @AfterEach
    // Метод остановки TomCat
    void stopTomcat() throws IOException, InterruptedException 
    {
        System.out.println("Stop Tomcat...");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "shutdown.bat");
        pb.directory(new File(TOMCAT_BIN));
        pb.start();
        Thread.sleep(TOMCAT_SHUTDOWN_TIMEOUT_MS);
    }
 
   
   // Метод который обновляет параметры MAxActive и MaxWaitMillis в context.xml перед стартом TomCat: 
   private void updateContextXml(TestConfig config) throws Exception
   {
        // 1. Динамическое определение пути относительно корня проекта
        // Мы берем текущую рабочую директорию (TestClient) и идем на уровень выше к папке tomcat
        File projectDir = new File(System.getProperty("user.dir")); // Это папка TestClient
        File tomcatConfDir = new File(projectDir.getParentFile(), "tomcat/conf/context.xml");

        String contextPath = tomcatConfDir.getCanonicalPath();
        File xmlFile = new File(contextPath);

        if (!xmlFile.exists()) {
            throw new FileNotFoundException("Critical error: File is not found: " + contextPath + 
                ". Make sure that folder tomcat is located in the root of TestPool near to TestClient!");
        }

        System.out.println(">>> [CONFIG] Read context.xml: " + contextPath);

        // 2. Инициализируем XML-парсер
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlFile);

        // 3. Ищем теги <Resource> и обновляем атрибуты
        NodeList resources = doc.getElementsByTagName("Resource");
        boolean isUpdated = false;

        for (int i = 0; i < resources.getLength(); i++) {
            Element resource = (Element) resources.item(i);
            if (resource.getAttribute("driverClassName").contains("postgresql") || 
                resource.getAttribute("name").contains("jdbc/postgres")) {

                resource.setAttribute("maxTotal", String.valueOf(config.maxActive));
                resource.setAttribute("maxWaitMillis", String.valueOf(config.maxWait));
                isUpdated = true;
            }
        }

        if (!isUpdated) {
            throw new RuntimeException("Error: In the context.xml tag Resource is not found (for PostgreSQL)!");
        }

        // --- ИСПРАВЛЕНИЕ: Очистка лишних переносов строк ---
        // Находим все текстовые узлы, которые состоят только из пробелов/переносов и удаляем их
        XPath xPath = XPathFactory.newInstance().newXPath();
        NodeList nodeList = (NodeList) xPath.evaluate("//text()[normalize-space()='']", doc, XPathConstants.NODESET);

        for (int i = 0; i < nodeList.getLength(); ++i) {
            Node node = nodeList.item(i);
            node.getParentNode().removeChild(node);
        }
        // --------------------------------------------------

        // 4. Сохраняем изменения обратно в файл
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();

        // Настройки форматирования
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org}indent-amount", "4");
        // Убираем standalone="no" для чистоты
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(xmlFile);
        transformer.transform(source, result);

        // 5. Завершение
        System.out.flush();
        Thread.sleep(500); 
        System.out.println(">>> [CONFIG] Parameters updated: maxTotal = " + config.maxActive + ", maxWaitMillis = " + config.maxWait);
    
    } // End of UpdateContextXml
    
    
    // **************************************************************************************************
    // Самый важный метод - наш параметризованный тест. 
    //***************************************************************************************************
    static List<TestConfig> getScenarios() 
    {
        return SCENARIOS;
    }
    
    @Feature("Postgres Connection Pool")
    @Story("MaxActive limit check")
    @Description("Проверка очереди Tomcat: запроса сразу, и в ожидании.")
    
    // Параметризованный запуск
    @ParameterizedTest(name = "{0}")
    @MethodSource("getScenarios")
    
    public void testPostgresPool(TestConfig testScenarioParameters) throws Exception 
    {
        int N = testScenarioParameters.threads; // Будем забирать количество потоков для запуска из параметров теста!
         
        TomcatPoolMonitor monitor = new TomcatPoolMonitor("jdbc/MyDataSource"); // Для запуска монитора в отдельном  потоке
        Thread monitorThread = new Thread(monitor); 
        
        System.out.println("\n*******************************************************************************************************\n");
         System.out.println("  Run test #: " + testScenarioParameters.number);
         System.out.println("  Conditions: " + testScenarioParameters.name + "  .\n");
         System.out.println("*******************************************************************************************************");
         
              
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        String url = "http://localhost:8080/MyServletProject/MyServlet";

        System.out.println("\n>>> Start of test... Run requests to servlet...");

        //Шаг 1: Добавим очистку директория work в TomCat чтобы гарантировать чтение параметров из context.xml
        Allure.step("1. Clean  Tomcat's cash (work & temp)", () -> 
        {
        File workDir = new File(TOMCAT_BIN + "/../work");
        File tempDir = new File(TOMCAT_BIN + "/../temp");

        if (workDir.exists()) deleteDirectory(workDir);
        if (tempDir.exists()) deleteDirectory(tempDir);

        System.out.println(">>> [CLEANUP] Direstories work and temp were cleaned.");
        });
        
        // Шаг 2. Перед тестом заполним правильными значениями MAxActive и MaxWaitMillis
        Allure.step("2. Preparation: pool parameters setup (maxTotal=" + testScenarioParameters.maxActive + ", maxWait=" + testScenarioParameters.maxWait + ")", () -> 
        {
            updateContextXml(testScenarioParameters);
        });
       
        // Шаг 3.1: Запустим TomCat:
        Allure.step("3.1 Start Tomcat", () -> 
        {
            try 
            {
                startTomcat();
                // Даем Tomcat время "проснуться" и создать MBean-ы
                Thread.sleep(3000); 
                System.out.println("TomCat has been started successfully.\n");
            } 
            catch (Exception e) 
            {
                throw new RuntimeException("Tomcat can't start: " + e.getMessage());
            }
        });
        
        // Для отладки: 
        //Thread.sleep(1000);
        
        // Шаг 3.2: Запустим монитор для наблюдения за значениями numActive в MBean TomCat:
        Allure.step("3.2 Start monitor MBean TomCat", () -> 
        {    
            monitorThread.start();
            System.out.println("MONITOR MBean: Monitor MBean has been started.");
        });
        
        ////////////////////////////////////////////////////////////////////////////////////////////////////////////
        // Шаг 3.3: Запустим монитор для наблюдением в количеством реальных коннектов в системной таблице PostgreSql: 
        System.out.println("Monitor DB: Initialisation start...");   

        // Код старта монитора DB
        System.out.println("\nMONITOR DB Initialisaion phase.");
        
        System.out.println("Monitor DB: Initialisation is finished."); 
        /////////////////////////////////////////////////////////////////////////////////////////////////////////////
                   
        // ВОТ ЗДЕСЬ ОБНУЛЯЕМ ТАЙМЕР:
        Instant testStart = Instant.now(); 
        System.out.println("\n>>> Test run (Time start: 0s)");

        // ШАГ 4. ЗАПУСКАЕМ N потоков с HTTP-запросом GET:
        List<TestResult> results = Allure.step("4. Executing of  " + N + " parallel requests to servlet", () -> 
        {
            List<CompletableFuture<TestResult>> futures = IntStream.rangeClosed(1, N) // Т - число запускаемых клиентских запросов (фич / потоков)
                .mapToObj(id -> CompletableFuture.supplyAsync
                            (   () -> 
                                    {
                                        Instant requestStart = Instant.now();
                                        try {
                                            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
                                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                                            long duration = Duration.between(requestStart, Instant.now()).toSeconds();
                                            long startOffset = Duration.between(testStart, requestStart).toSeconds();

                                            
                                              // --- ВОТ ЭТОТ БЛОК ВЫВЕДЕТ ВСЁ В КОНСОЛЬ ---
                                                synchronized (System.out) 
                                                {
                                                    System.out.println("\n[HTTP RESPONSE DEBUG] Request ID: " + id);
                                                    System.out.println("Status Code: " + response.statusCode());
                                                    System.out.println("Body: " + response.body());
                                                    System.out.println("----------------------");
                                                }
                                                
                                            return new TestResult(response.statusCode(), startOffset, duration, response.body());
                                        } catch (Exception e) {
                                            return new TestResult(500, -1, -1,"");
                                        }
                                    }
                            )
                         )
                .collect(Collectors.toList());

            // Ждем здесь же, внутри шага, чтобы Allure замерил полное время выполнения всех потоков
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            // Возвращаем результат из шага наружу в переменную results
            return futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        });
        
        //Шаг 5.1: Останавливаем монитор значений в MBean TomCat:
        Allure.step("5.2 Stop monitor MBean", () -> 
        {
            monitor.stop(); // Останавливаем цикл в потоке
            try 
            {
                monitorThread.join(2000); // Ждем завершения потока (макс 2 секунды)
                System.out.println("MONITOR MBean: monitor MBean has been stopped.");
            } 
            catch (InterruptedException e) 
            {
                Thread.currentThread().interrupt(); // Восстанавливаем статус прерывания
            }
            
            // Сразу добавляем результат в отчет Allure для наглядности
            tempMaxNumActive = monitor.getMaxObservedActive();
            Allure.addAttachment("Peak of connections (numActive):", String.valueOf(tempMaxNumActive));
            System.out.println("MONITOR MBean: Peak of connections (numActive):" + tempMaxNumActive);         
        });

        
        //Шаг 5.2: Останавливаем TomCat:
        Allure.step("5.2 Stop Tomcat", () -> 
        {
            stopTomcat();
            System.out.println("TomCat: TomCat has been stopped.\n");
        });
        
        ///////////////////////////////////////////////////////////////////////////////////////////////////////////
        //Шаг 5.3: Останавливаем монитор DB:         
        System.out.println("\nMonitor DB: Finalisation start...");   

        // Код старта монитора DB
        System.out.println("MONITOR DB final phase.");
        
        System.out.println("Monitor DB: Monitor DB is finished.\n"); 
        ///////////////////////////////////////////////////////////////////////////////////////////////////////////
      
        // ШАГ 6: Формируем детальный отчет:
        Allure.step("6. Detail repost preparation...", () -> 
        {
            int Error = 0;
            System.out.println("\n======= Detail report =======");
            for (int i = 0; i < results.size(); i++) 
            {
                TestResult r = results.get(i);
                // Проверим были ли sql ошибки в response (то есть отвалился ли поток по таймауту?):
                if (r.body.contains("SQL query execution error")== true)
                {
                    Error = 1;                    
                }
                else
                    Error = 0;
                
                String logLine = String.format("Request #%d | Start on %d sec | Duration %d sec | StatusHttp: %d | TimeoutError: %d | MaxWait: %d | Sleep: %d ", 
                                               (i + 1), r.startOffset, r.duration, r.code, Error, testScenarioParameters.maxWait, testScenarioParameters.clientSleep);
                System.out.println(logLine);
                // Добавляем строчку лога прямо в Allure как вложение или текст
                Allure.addAttachment("Request " + (i+1), logLine);
            }
        });
        
        // ШАГ 7: Логирование в отчет Allure и универсальные проверки Asserts
        Allure.step("7. Results checking (Assertions)", () -> 
        {
           
             System.out.println("\n======= ASSERT CHECKING FILTER #1: (ODINARY CHECK)=======");
            // 0. ОБЪЯВЛЯЕМ переменные в начале блока, чтобы их видели все вложенные шаги
            final long sleep = testScenarioParameters.clientSleep / 1000; 
            final int maxActive = testScenarioParameters.maxActive;

            // 1. Считаем РЕАЛЬНЫЕ успехи (код 200 И в теле нет фразы об ошибке)
            long realOkCount = results.stream()
                .filter(r -> r.code == 200 && !r.body.contains("SQL query execution error"))
                .count();

            // 2. Считаем РЕАЛЬНЫЕ ошибки (либо код 500, либо 200 с текстом ошибки)
            long realErrorCount = results.stream()
                .filter(r -> r.code == 500 || (r.code == 200 && r.body.contains("SQL query execution error")))
                .count();

            // 3. Проверка количества
            Allure.step("7.1 Проверка: Успешных ответов с данными (ожидаем " + testScenarioParameters.expectedOk + ")", () -> 
                assertEquals(testScenarioParameters.expectedOk, realOkCount, "7.1 Qunitiy of 200 respones in not correct. Кол-во чистых 200 не совпало!")
            );  
            System.out.println(testScenarioParameters.expectedOk == realOkCount ? "ASSERT 7.1: SUCCESS" : "ASSERT 7.1: ERROR!");    //Выведем сразу в консоль результат.
                        
            Allure.step("7.2 Проверка: Отвалов пула (ожидаем " + testScenarioParameters.expectedError + ")", () -> 
                assertEquals(testScenarioParameters.expectedError, realErrorCount, "7.2 Expected errors are absent. Ожидаемые ошибки не найдены в ответах!")
            );
            System.out.println(testScenarioParameters.expectedError == realErrorCount ? "ASSERT 7.2: SUCCESS" : "ASSERT 7.2: ERROR!");    //Выведем сразу в консоль результат.

            // 4. Лесенка времени (только для тех, где реально были данные)
            List<TestResult> sortedOkResults = results.stream()
                .filter(r -> r.code == 200 && !r.body.contains("SQL query execution error"))
                .sorted(Comparator.comparingLong(r -> r.duration))
                .collect(Collectors.toList());

            // 5. Если были позитивные результаты: 
            if (sortedOkResults.size() > 0) 
            {    
                final int delta = 2; // Допустимая погрешность в секундах (запас на лаги ОС/сети)
                
                for (int i = 0; i < sortedOkResults.size(); i++)  
                {
                    final int requestIndex = i;
                    // Номер "волны" (0, 1, 2...), в которую попал запрос исходя из maxActive
                    int wave = i / maxActive; 
                    long expectedDuration = sleep * (wave + 1);

                    Allure.step("7.3."+ i +" Check request in wave #" + (wave + 1) + " (expect ~" + expectedDuration + "seconds)", () -> 
                    {
                        long actualDuration = sortedOkResults.get(requestIndex).duration;
                        // Вот тут были срабатывания ассертов из-за задержек в на 1-2 секунды:
                        assertTrue(Math.abs(actualDuration - expectedDuration) <= delta, 
                            "Request #" + (requestIndex + 1) + " in queue waited " + actualDuration + "instead of  " + expectedDuration + "seconds.");

                        // 1. Сначала вычисляем статус
                        String status = (Math.abs(actualDuration - expectedDuration) <= 1) ? "SUCCESS" : "ERROR!";
                        // 2. Затем печатаем
                        System.out.println("ASSERT 7.3." + requestIndex + ": " + status);
                    });
                }
            }
            else  // Если были ТОЛЬКО негативные результаты: 
            {
                Allure.step("7.3 CHECK only negative results: Проверка: если только негативный результат - все отвалилось по таймауту", () -> 
                assertEquals(testScenarioParameters.expectedOk, realOkCount, "Кол-во !")
                );  
                System.out.println("ASSERT 7.3: SUCCCESS. (only negative results expected)" );
            }
            System.out.println("======= ASSERT CHECKING FILTER #1: END. ======================="); 
            
            /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
            
            System.out.println("\n======= ASSERT CHECKING FILTER #2: (TOMCAT MBEAN CHECK)=======");
            
            // Второй фильтр на основе результатов работы монитора TomCat MBean:
            // Главная проверка: MBean не должен зафиксировать значение выше лимита MaxTotal
            
            assertTrue(tempMaxNumActive != -1, "8.0 Monitor MBean was not connected to TomCat MBean");
            if (tempMaxNumActive == -1)
            {
                 System.out.println("ASSERT 8.0: Error: Monitor MBean was not connected to TomCat MBean");                
            }
            else
            {
                System.out.println("ASSERT 8.0: SUCCESS");
            }
            
           assertTrue(tempMaxNumActive <= testScenarioParameters.maxActive, "8.1 Threshold limit exceeded! Detected: " + tempMaxNumActive);
           // Сначала вычисляем статус
           String statusMBeanCheck = (tempMaxNumActive <= testScenarioParameters.maxActive) ? "SUCCESS" : "ERROR!";
           // 2. Затем печатаем
           System.out.println("ASSERT 8.1: " + statusMBeanCheck);
           System.out.println("======= ASSERT CHECKING FILTER #2: END. ======================="); 
           
           ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
           // Проверка результатов работы  третьего фильтра:          
           System.out.println("\n======= ASSERT CHECKING FILTER #3: (PostgreSql DB CHECK)=======");
           
           // Код валидации результатов работы потока монитора DB
           System.out.println(" MUST BE ADDED HERE! PRINT RESULT OF FILTER #3 CHECKING.");
           
           System.out.println("======= ASSERT CHECKING FILTER #3: END. =======================");
           ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
            
        }); // Конец главного Allure.step
        
        System.out.println("\n============ All steps have been completed. Wow! :-) ==================\n");

    } // End of test 
    
    // Метод очистки директория в TomCat
    private void deleteDirectory(File directoryToBeDeleted) 
    {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) 
        {
            for (File file : allContents) 
            {
                deleteDirectory(file); // Рекурсивно заходим в папки
            }
        }
        directoryToBeDeleted.delete(); // Удаляем сам файл или пустую папку
    }

} // End of class TestMaxActive