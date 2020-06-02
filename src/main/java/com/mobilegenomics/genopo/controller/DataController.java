package com.mobilegenomics.genopo.controller;

import com.mobilegenomics.genopo.core.Step;
import com.mobilegenomics.genopo.dto.State;
import com.mobilegenomics.genopo.dto.WrapperObject;
import com.mobilegenomics.genopo.support.TimeFormat;
import com.vaadin.data.provider.DataProviderListener;
import com.vaadin.data.provider.ListDataProvider;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;

public class DataController {

    // TODO let the user assign the following values
    public static final long statWatchTimerInMinutes = 1; // 1 minute
    static ListDataProvider<WrapperObject> idleListDataProvider;
    static ListDataProvider<WrapperObject> busyListDataProvider;
    private static Long accumulatedJobProcessTime = 0L;
    private static Long averageProcessingTime = 2700000L;//45 mins
    private static Long userSetTimeout = 0L;
    private static boolean isTimeoutSetByUser = false;
    private static Long successJobs = 0L;
    private static String pathToDataDir;
    private static StringBuilder serverLogBuffer = new StringBuilder();
    private static ArrayList<WrapperObject> idleWrapperObjectList;
    private static ArrayList<WrapperObject> pendingWrapperObjectList;
    private static String SPLITTER = ".zip";
    private static ArrayList<String> filePrefixes = new ArrayList<>();
    private static WatchService watchService;
    private static int idleJobLimit = 10;

    private static float jobCompletionRate = 0;
    private static float jobFailureRate = 0;
    private static float newJobArrivalRate = 0;
    private static float newJobRequestRate = 0;

    private static int totalRunningJobs = 0;
    private static int totalIdleJobs = 0;
    private static int totalPredictedRunningJobs = 0;
    private static int totalPredictedIdleJobs = 0;

    public static ListDataProvider<WrapperObject> setListDataProvider(State state) {
        if (state == State.IDLE) {
            idleListDataProvider = new ListDataProvider(idleWrapperObjectList);
            return idleListDataProvider;
        } else {
            pendingWrapperObjectList = new ArrayList<>();
            busyListDataProvider = new ListDataProvider<>(pendingWrapperObjectList);
            return busyListDataProvider;
        }
    }

    static void readFilesFromDir() {
        File folder = new File(pathToDataDir);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                if (file.isFile()) {
                    String[] prefix = file.getName().split(SPLITTER);
                    filePrefixes.add(prefix[0]);
                }
            }
        }
        createOutputDirectory(pathToDataDir);
    }

    static void createOutputDirectory(String pathToDir) {
        File outputDir = new File(pathToDir + "/outputs");
        if (!outputDir.exists()) {
            outputDir.mkdir();
        }
    }

    private static void fileDirMonitorAttach(boolean isAutomate) {
        if (isAutomate) {
            new Thread(() -> {
                int filesCount = Objects.requireNonNull(new File(pathToDataDir).list()).length;
                System.out.println(filesCount);

                try {
                    watchService = FileSystems.getDefault().newWatchService();
                    Path path = Paths.get(pathToDataDir);
                    path.register(
                            watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY);

                    WatchKey key;
                    while ((key = watchService.take()) != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            String file = event.context().toString();
                            String prefix = file.split(SPLITTER)[0];
                            System.out.println("Event kind:" + event.kind() + ". File affected: " + file);
                            filePrefixes.add(prefix);
                            ArrayList<Step> steps = new ArrayList<>(CoreController.getSteps().values());
                            WrapperObject newWrapperObject = new WrapperObject(prefix, State.IDLE, pathToDataDir, steps);
                            updateGrids(newWrapperObject);
                        }
                        key.reset();
                    }
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
        } else {
            fileDirMonitorDetach();
        }
    }

    public static void fileDirMonitorDetach() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static WrapperObject addServerSideReport(WrapperObject wrapper) {
        StringBuilder serverSideReport = new StringBuilder();
        serverSideReport.append(wrapper.getResultSummery());
        serverSideReport.append("\n");
        serverSideReport.append("Processed Job Prefix: ").append(wrapper.getPrefix());
        serverSideReport.append("\n");
        serverSideReport.append("Client Address: ").append(wrapper.getClientIP());
        serverSideReport.append("\n");
        String jobProcessTime = TimeFormat.millisToShortDHMS(wrapper.getCollectTime() - wrapper.getReleaseTime());
        serverSideReport.append(String.format("Total Job Processing Time: %s", jobProcessTime));
        wrapper.setResultSummery(serverSideReport.toString());
        return wrapper;
    }

    public static void writeSummaryLogToFile(String datasetName, String summary) {
        File summaryFile = new File(pathToDataDir + "/outputs/" + datasetName + "_result_summary.txt");
        try {
            summaryFile.createNewFile(); // if file already exists will do nothing
            PrintWriter writer = new PrintWriter(summaryFile, "UTF-8");
            writer.write(summary);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void generateServerLog() {
        File serverLogFile = new File(pathToDataDir + "/outputs/f5n_server_log_" + TimeFormat.currentDateTime() + ".txt");
        try {
            serverLogFile.createNewFile(); // if file already exists will do nothing
            PrintWriter writer = new PrintWriter(serverLogFile, "UTF-8");
            writer.write(serverLogBuffer.toString());
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void createWrapperObjects(boolean isAutomate) {
        readFilesFromDir();
        idleWrapperObjectList = new ArrayList<>();
        WrapperObject newWrapperObject;
        ArrayList<Step> steps = new ArrayList<>(CoreController.getSteps().values());
        for (String prefix : filePrefixes) {
            newWrapperObject = new WrapperObject(prefix, State.IDLE, "http://" + getLocalIPAddress() + ":8000/", steps);
            idleWrapperObjectList.add(newWrapperObject);
        }
        fileDirMonitorAttach(isAutomate);
    }

    public static void clearWrapperObjects() {
        getIdleWrapperObjectList().clear();
        getPendingWrapperObjectList().clear();
    }

    public static void updateGrids(WrapperObject object) {
        if (object.getState().equals(State.IDLE)) {
            DataController.idleListDataProvider.getItems().add(object);
        } else if (object.getState().equals(State.PENDING)) {
            DataController.idleListDataProvider.getItems().remove(object);
            DataController.busyListDataProvider.getItems().add(object);
        } else if (object.getState().equals(State.SUCCESS)) {
            boolean isValidClient = DataController.busyListDataProvider.getItems().removeIf(item ->
                    (item.getPrefix().equals(object.getPrefix()) && item.getClientIP().equals(object.getClientIP())));
            if (isValidClient) {
                DataController.busyListDataProvider.getItems().add(object);
            }
        }
        refreshGrids();
    }

    private static void refreshGrids() {
        if (DataController.idleListDataProvider != null) {
            DataController.idleListDataProvider.refreshAll();
        }
        if (DataController.busyListDataProvider != null) {
            DataController.busyListDataProvider.refreshAll();
        }
    }

    public static ArrayList<String> getFilePrefixes() {
        return filePrefixes;
    }

    public static ArrayList<WrapperObject> getIdleWrapperObjectList() {
        return idleWrapperObjectList;
    }

    public static ArrayList<WrapperObject> getPendingWrapperObjectList() {
        return pendingWrapperObjectList;
    }

    public static void configureJobProcessTime(WrapperObject wrapperObject) {
        calculateAccumulateJobProcessTime(wrapperObject);
        incrementSuccessJobs();
        calculateAverageJobProcessingTime();
    }

    public static void calculateAccumulateJobProcessTime(WrapperObject wrapperObject) {
        accumulatedJobProcessTime = accumulatedJobProcessTime + (wrapperObject.getCollectTime() - wrapperObject.getReleaseTime());
    }

    public static void calculateAverageJobProcessingTime() {
        averageProcessingTime = accumulatedJobProcessTime / successJobs;
    }

    public static Long getAverageProcessingTime() {
        return averageProcessingTime / 1000;
    }

    public static void setAverageProcessingTime(Long averageProcessingTime) {
        DataController.averageProcessingTime = averageProcessingTime * 1000;
    }

    public static Long getProcessingTime() {
        if (!isTimeoutSetByUser) {
            return averageProcessingTime;
        } else {
            return userSetTimeout;
        }
    }

    public static void incrementSuccessJobs() {
        successJobs = successJobs + 1;
    }

    public static void decrementSuccessJobs() {
        successJobs = successJobs - 1;
    }

    public static String getLocalIPAddress() {
        String ip = "127.0.0.1";
        final DatagramSocket socket;
        try {
            socket = new DatagramSocket();
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            ip = socket.getLocalAddress().getHostAddress();
        } catch (SocketException | UnknownHostException e) {
            e.printStackTrace();
        }
        return ip;
    }

    public static void calculateStats() {
        calculateJobCompletionRate();
        calculateJobFailureRate();
        calculateNewJobArrivalRate();
        calculateNewJobRequestRate();
    }

    public static float getJobCompletionRate() {
        return jobCompletionRate;
    }

    public static float getJobFailureRate() {
        return jobFailureRate;
    }

    public static float getNewJobArrivalRate() {
        return newJobArrivalRate;
    }

    public static float getNewJobRequestRate() {
        return newJobRequestRate;
    }

    // Job Completion rate is equal 1 / averageProcessingTime
    private static void calculateJobCompletionRate() {
        jobCompletionRate = 1.0f / averageProcessingTime;
    }

    // Job Failure rate is equal to jobs could not complete before timeout
    private static void calculateJobFailureRate() {
        Long elapsedTime;
        int totalTimeOutJobs = 0;
        Iterator<WrapperObject> iterator = pendingWrapperObjectList.iterator();
        while (iterator.hasNext()) {
            WrapperObject wrapperObject = iterator.next();
            if (wrapperObject.getState() == State.PENDING) {
                elapsedTime = (System.currentTimeMillis() - wrapperObject.getReleaseTime()) / 1000;
                if (elapsedTime > getProcessingTime()) {
                    totalTimeOutJobs++;
                }
            }
        }
        jobCompletionRate = totalTimeOutJobs / (float) statWatchTimerInMinutes;
    }

    // Calculate New Job Arrival rate and update total Idle Jobs
    private static void calculateNewJobArrivalRate() {
        if (filePrefixes.size() - totalIdleJobs <= 0) {
            newJobRequestRate = 0;
        } else {
            newJobArrivalRate = (filePrefixes.size() - totalIdleJobs) / (float) statWatchTimerInMinutes;
        }
        totalIdleJobs = filePrefixes.size();
    }

    // Calculate New Job Request rate and update total running Jobs
    private static void calculateNewJobRequestRate() {
        newJobRequestRate = (pendingWrapperObjectList.size() - totalRunningJobs) / (float) statWatchTimerInMinutes;
        totalRunningJobs = pendingWrapperObjectList.size();
    }

    // TODO Complete the following two prediction methods relating idleJobLimit
    private static void predictNumberOfIdleJobs(long time) {
        totalPredictedIdleJobs = totalIdleJobs - (int) (newJobRequestRate * time) + (int) (newJobArrivalRate * time) + (int) (jobFailureRate * time);
    }

    private static void predictNumberOfRunningJobs(long time) {
        totalPredictedRunningJobs = totalRunningJobs - (int) (jobCompletionRate * totalRunningJobs) + (int) (newJobRequestRate * time);
    }

    public static String getPathToDataDir() {
        return pathToDataDir;
    }

    public static void setPathToDataDir(String pathToDataDir) {
        DataController.pathToDataDir = pathToDataDir;
    }

    public static void runLogger() {
        logServer("####################### Server log on " + TimeFormat.currentDateTime() + " #######################");
        logServer("\nServer started at " + TimeFormat.currentDateTime());
        if (!idleWrapperObjectList.isEmpty()) {
            logServer("\nPipeline Steps" + idleWrapperObjectList.get(0).toStringPretty());
            logServer("File server at: " + idleWrapperObjectList.get(0).getPathToDataDir());
            logServer("\n");
            idleListDataProvider.addDataProviderListener((DataProviderListener<WrapperObject>) event -> {
                logServer("------------------ IDLE Jobs at " + TimeFormat.currentDateTime() + " ------------------");
                logServer("Remaining idle jobs: " + idleListDataProvider.getItems().size());
                for (WrapperObject wrapperObject : idleListDataProvider.getItems()) {
                    logServer("Idle   " + wrapperObject.getPrefix());
                }
            });
            logServer("\n\n");
        }

        busyListDataProvider.addDataProviderListener((DataProviderListener<WrapperObject>) event -> {
            logServer("------------ PENDING/COMPLETE Jobs at " + TimeFormat.currentDateTime() + " ------------");
            logServer("Pending/Complete jobs: " + busyListDataProvider.getItems().size());
            for (WrapperObject wrapperObject : busyListDataProvider.getItems()) {
                if (wrapperObject.getCollectTime() != null) {
                    logServer(wrapperObject.getState().name() + "   " + wrapperObject.getPrefix() + "   " + TimeFormat.millisToDateTime(wrapperObject.getReleaseTime()) + "   " + TimeFormat.millisToDateTime(wrapperObject.getCollectTime()) + "   " + wrapperObject.getClientIP());
                } else {
                    logServer(wrapperObject.getState().name() + "   " + wrapperObject.getPrefix() + "   " + wrapperObject.getReleaseTime() + "   " + "---" + "   " + wrapperObject.getClientIP());
                }
            }
            logServer("\n");
        });
    }

    public StringBuilder getServerLogBuffer() {
        return serverLogBuffer;
    }

    public static void logServer(String log) {
        serverLogBuffer.append(log).append("\n");
    }
}