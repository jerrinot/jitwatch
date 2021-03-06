/*
 * Copyright (c) 2013 Chris Newland. All rights reserved.
 * Licensed under https://github.com/chriswhocodes/jitwatch/blob/master/LICENSE-BSD
 * http://www.chrisnewland.com/jitwatch
 */
package com.chrisnewland.jitwatch.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

import com.chrisnewland.jitwatch.model.IMetaMember;
import com.chrisnewland.jitwatch.model.JITDataModel;
import com.chrisnewland.jitwatch.util.ClassUtil;
import com.chrisnewland.jitwatch.util.ParseUtil;
import com.chrisnewland.jitwatch.util.StringUtil;

public class HotSpotLogParser
{
    enum EventType
    {
        QUEUE, NMETHOD, TASK
    }

    private JITDataModel model;

    private boolean watching = false;

    private boolean inNativeCode = false;

    private StringBuilder nativeCodeBuilder = new StringBuilder();

    private IMetaMember currentMember = null;

    private IJITListener logListener = null;

    private long currentLineNumber;

    private JITWatchConfig config;

    public HotSpotLogParser(JITDataModel model, JITWatchConfig config, IJITListener logListener)
    {
        this.model = model;

        this.logListener = logListener;

        this.config = config;
    }

    private void mountAdditionalClasses()
    {
        for (String filename : config.getClassLocations())
        {
            URI uri = new File(filename).toURI();

            logListener.handleLogEntry("Adding classpath: " + uri.toString());

            ClassUtil.addURIToClasspath(uri);
        }
    }

    private void logEvent(JITEvent event)
    {
        if (logListener != null)
        {
            logListener.handleJITEvent(event);
        }
    }

    private void logError(String entry)
    {
        if (logListener != null)
        {
            logListener.handleErrorEntry(entry);
        }
    }

    public void watch(File hotspotLog) throws IOException
    {
        mountAdditionalClasses();

        currentLineNumber = 0;

        BufferedReader input = new BufferedReader(new FileReader(hotspotLog));

        String currentLine = null;

        watching = true;

        while (watching)
        {
            if (currentLine != null)
            {
                handleLine(currentLine);
            }
            else
            {
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                    break;
                }
            }

            currentLine = input.readLine();
        }

        input.close();
    }

    public void stop()
    {
        watching = false;
    }

    private void handleLine(String currentLine)
    {
        currentLine = currentLine.replace("&apos;", "'");
        currentLine = currentLine.replace("&lt;", "<");
        currentLine = currentLine.replace("&gt;", ">");

        Map<String, String> attrs = StringUtil.getLineAttributes(currentLine);

        try
        {
            // ok this is starting to get ugly, find a better way!
            if (currentLine.startsWith(JITWatchConstants.TAG_TASK_QUEUED))
            {
                if (inNativeCode)
                {
                    completeNativeCode();
                }
                handleMethodLine(currentLine, attrs, EventType.QUEUE);
            }
            else if (currentLine.startsWith(JITWatchConstants.TAG_NMETHOD))
            {
                if (inNativeCode)
                {
                    completeNativeCode();
                }
                handleMethodLine(currentLine, attrs, EventType.NMETHOD);
            }
            else if (currentLine.startsWith(JITWatchConstants.TAG_TASK))
            {
                if (inNativeCode)
                {
                    completeNativeCode();
                }
                handleMethodLine(currentLine, attrs, EventType.TASK);
            }
            else if (currentLine.startsWith(JITWatchConstants.TAG_TASK_DONE))
            {
                if (inNativeCode)
                {
                    completeNativeCode();
                }
                handleTaskDone(currentLine);
            }
            else if (currentLine.startsWith(JITWatchConstants.LOADED))
            {
                if (inNativeCode)
                {
                    completeNativeCode();
                }
                handleLoaded(currentLine);
            }
            else if (currentLine.startsWith(JITWatchConstants.TAG_START_COMPILE_THREAD))
            {
                if (inNativeCode)
                {
                    completeNativeCode();
                }
                handleStartCompileThread();
            }
            else if (currentLine.contains(JITWatchConstants.NATIVE_CODE_METHOD_MARK))
            {
                String sig = convertNativeCodeMethodName(currentLine);

                currentMember = findMemberWithSignature(sig);
                inNativeCode = true;

                appendNativeCode(currentLine);

            }
            else if (inNativeCode)
            {
                appendNativeCode(currentLine);
            }
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        
        String compileID = attrs.get(JITWatchConstants.ATTR_COMPILE_ID);
        String compileKind = attrs.get(JITWatchConstants.ATTR_COMPILE_KIND);

        String journalID;
        
        // osr compiles do not have unique compile IDs so concat compile_kind
        if (compileID != null && compileKind != null && JITWatchConstants.OSR.equals(compileKind))
        {
            journalID = compileID + compileKind;
        }
        else
        {
            journalID = compileID;
        }

        if (compileID != null)
        {
            model.addJournalEntry(journalID, currentLine);
        }

        currentLineNumber++;

    }

    private void appendNativeCode(String line)
    {
        nativeCodeBuilder.append(line).append("\n");
    }

    private void completeNativeCode()
    {
        inNativeCode = false;

        if (currentMember != null)
        {
            currentMember.setNativeCode(nativeCodeBuilder.toString());
        }

        nativeCodeBuilder.delete(0, nativeCodeBuilder.length());
    }

    private void handleStartCompileThread()
    {
        model.getJITStats().incCompilerThreads();
    }

    private void handleMethodLine(String currentLine, Map<String, String> attrs, EventType eventType)
    {
        String fqMethodName = StringUtil.getSubstringBetween(currentLine, JITWatchConstants.METHOD_START, "'");

        if (fqMethodName != null)
        {
            fqMethodName = fqMethodName.replace("/", ".");

            boolean packageOK = config.isAllowedPackage(fqMethodName);

            if (packageOK)
            {
                attrs.remove("method");
                handleMethod(fqMethodName, attrs, eventType);
            }
        }
    }

    private IMetaMember findMemberWithSignature(String logSignature)
    {
        IMetaMember metaMember = null;

        String[] parsedResult = null;

        try
        {
            parsedResult = ParseUtil.parseLogSignature(logSignature);
        }
        catch (Exception e)
        {
            logError(e.getMessage());
        }

        if (parsedResult != null)
        {
            String className = parsedResult[0];
            String parsedSignature = parsedResult[1];

            if (parsedSignature != null)
            {
                metaMember = model.findMetaMember(className, parsedSignature);
            }
        }
        else
        {
            logError("Could not parse line " + currentLineNumber + " : " + logSignature);
        }

        return metaMember;
    }

    private void handleMethod(String methodSignature, Map<String, String> attrs, EventType type)
    {
        IMetaMember metaMember = findMemberWithSignature(methodSignature);

        String stampAttr = attrs.get("stamp");
        long stampTime = (long) (Double.parseDouble(stampAttr) * 1000);

        if (metaMember != null)
        {
            switch (type)
            {
            case QUEUE:
                metaMember.setQueuedAttributes(attrs);
                JITEvent queuedEvent = new JITEvent(stampTime, false, metaMember.toString());
                model.addEvent(queuedEvent);
                logEvent(queuedEvent);
                break;
            case NMETHOD:
                metaMember.setCompiledAttributes(attrs);
                metaMember.getMetaClass().incCompiledMethodCount();
                model.updateStats(metaMember);

                JITEvent compiledEvent = new JITEvent(stampTime, true, metaMember.toString());
                model.addEvent(compiledEvent);
                logEvent(compiledEvent);
                break;
            case TASK:
                metaMember.addCompiledAttributes(attrs);
                currentMember = metaMember;
                break;
            }
        }
    }

    private void handleTaskDone(String line)
    {
        Map<String, String> attrs = StringUtil.getLineAttributes(line);

        if (attrs.containsKey("nmsize"))
        {
            long nmsize = Long.parseLong(attrs.get("nmsize"));
            model.addNativeBytes(nmsize);
        }

        if (currentMember != null)
        {
            currentMember.addCompiledAttributes(attrs);

            // prevents attr overwrite by next task_done if next member not
            // found due to classpath issues
            currentMember = null;
        }
    }

    /*
     * JITWatch needs classloader information so it can show classes which have
     * no JIT-compiled methods in the class tree
     */
    private void handleLoaded(String currentLine)
    {
        String fqClassName = StringUtil.getSubstringBetween(currentLine, JITWatchConstants.LOADED, " ");

        if (fqClassName != null)
        {
            String packageName;
            String className;

            int lastDotIndex = fqClassName.lastIndexOf('.');

            if (lastDotIndex != -1)
            {
                packageName = fqClassName.substring(0, lastDotIndex);
                className = fqClassName.substring(lastDotIndex + 1);
            }
            else
            {
                packageName = "";
                className = fqClassName;
            }

            boolean allowedPackage = config.isAllowedPackage(packageName);

            if (allowedPackage)
            {
                Class<?> clazz = null;

                try
                {
                    clazz = ClassUtil.loadClassWithoutInitialising(fqClassName);
                }
                catch (ClassNotFoundException cnf)
                {
                    logError("ClassNotFoundException: '" + fqClassName + "' parsing " + currentLine);
                }
                catch (NoClassDefFoundError ncdf)
                {
                    logError("NoClassDefFoundError: '" + fqClassName + "' parsing " + currentLine);
                }

                try
                {
                    // can throw NCDFE from clazz.getDeclaredMethods()
                    model.buildMetaClass(packageName, className, clazz);
                }
                catch (NoClassDefFoundError ncdf)
                {
                    // missing class is from a method declaration in fqClassName
                    // so look in getMessage()
                    logError("NoClassDefFoundError: '" + ncdf.getMessage() + "' parsing " + currentLine);
                }
            }
        }
    }

    public String convertNativeCodeMethodName(String name)
    {
        name = name.replace("'", "");

        int methodMarkIndex = name.indexOf(JITWatchConstants.NATIVE_CODE_METHOD_MARK);

        if (methodMarkIndex != -1)
        {
            name = name.substring(methodMarkIndex + JITWatchConstants.NATIVE_CODE_METHOD_MARK.length());
            name = name.trim();
        }

        String inToken = " in ";

        int inPos = name.indexOf(inToken);

        if (inPos != -1)
        {
            name = name.substring(inPos + inToken.length()) + " " + name.substring(0, inPos);
        }

        name = name.replaceAll("/", ".");

        return name;
    }
}