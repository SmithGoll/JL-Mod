/*
 * Copyright 2020 Nikita Shakarun
 * Copyright 2021-2024 Yury Kharchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.crashes;

import static org.acra.ReportField.*;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Base64;
import android.view.Display;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;
import com.google.gson.Gson;

import org.acra.ReportField;
import org.acra.builder.ReportBuilder;
import org.acra.collector.Collector;
import org.acra.config.CoreConfiguration;
import org.acra.data.CrashReportData;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import ru.playsoftware.j2meloader.EmulatorApplication;
import ru.playsoftware.j2meloader.crashes.models.AbstractLog;
import ru.playsoftware.j2meloader.crashes.models.Attachment;
import ru.playsoftware.j2meloader.crashes.models.Device;
import ru.playsoftware.j2meloader.crashes.models.ErrorLog;
import ru.playsoftware.j2meloader.crashes.models.ExceptionModel;
import ru.playsoftware.j2meloader.crashes.models.RequestBody;
import ru.playsoftware.j2meloader.crashes.models.StackFrame;
import ru.playsoftware.j2meloader.util.Constants;

@AutoService(Collector.class)
public class AppCenterCollector implements Collector {
	public static final String APPCENTER_LOG = "APPCENTER_LOG";
	public static final List<ReportField> REPORT_FIELDS = Arrays.asList(
			ANDROID_VERSION, APP_VERSION_CODE, APP_VERSION_NAME, BRAND, CUSTOM_DATA, DISPLAY,
			INSTALLATION_ID, IS_SILENT, LOGCAT, PACKAGE_NAME, PHONE_MODEL, REPORT_ID,
			STACK_TRACE, USER_APP_START_DATE, USER_CRASH_DATE);

	@Override
	public void collect(@NonNull Context context,
						@NonNull CoreConfiguration config,
						@NonNull ReportBuilder reportBuilder,
						@NonNull CrashReportData crashReportData) {
		String log = createCrashLog(crashReportData, reportBuilder);
		crashReportData.put(APPCENTER_LOG, log);
	}

	@NonNull
	@Override
	public Order getOrder() {
		return Order.LAST;
	}

	private String createCrashLog(CrashReportData report, ReportBuilder reportBuilder) {
		ArrayList<AbstractLog> logs = new ArrayList<>();

		ErrorLog errorLog = new ErrorLog(report.getString(REPORT_ID));
		errorLog.appLaunchTimestamp = report.getString(USER_APP_START_DATE);
		errorLog.timestamp = report.getString(USER_CRASH_DATE);
		errorLog.userId = report.getString(INSTALLATION_ID);
		Thread uncaughtExceptionThread = reportBuilder.getUncaughtExceptionThread();
		if (uncaughtExceptionThread != null) {
			errorLog.errorThreadId = uncaughtExceptionThread.getId();
			errorLog.errorThreadName = uncaughtExceptionThread.getName();
		}

		errorLog.processId = Process.myPid();
		errorLog.processName = EmulatorApplication.getProcessName();

		Device device = new Device();
		device.appBuild = report.getString(APP_VERSION_CODE);
		device.appNamespace = report.getString(PACKAGE_NAME);
		device.appVersion = report.getString(APP_VERSION_NAME);
		device.model = report.getString(PHONE_MODEL);
		device.osVersion = report.getString(ANDROID_VERSION);
		device.osApiLevel = Build.VERSION.SDK_INT;
		device.oemName = report.getString(BRAND);
		device.locale = Locale.getDefault().toString();
		device.timeZoneOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60 / 1000;
		JSONObject displays = (JSONObject) report.get(DISPLAY.toString());
		if (displays != null) {
			JSONObject display = (JSONObject) displays.opt(Integer.toString(Display.DEFAULT_DISPLAY));
			if (display != null) {
				device.screenSize = display.optString("width") + "x" + display.optString("height");
			}
		}
		errorLog.device = device;

		errorLog.exception = getModelExceptionFromThrowable(reportBuilder.getException());
		logs.add(errorLog);

		StringBuilder sb = null;
		JSONObject o = (JSONObject) report.get(CUSTOM_DATA.name());
		if (o != null) {
			Object od = o.opt(Constants.KEY_APPCENTER_ATTACHMENT);
			if (od != null) {
				sb = new StringBuilder().append(od);
			}
		}

		String logcat = report.getString(LOGCAT);
		if (logcat != null) {
			if (sb == null) {
				sb = new StringBuilder(logcat);
			} else {
				sb.append("\n====================Logcat==================\n").append(logcat);
			}
		}
		if (sb != null) {
			Attachment logcatAttachment = new Attachment("attachment.txt");
			logcatAttachment.data = Base64.encodeToString(sb.toString().getBytes(), Base64.DEFAULT);
			logcatAttachment.errorId = report.getString(REPORT_ID);
			logcatAttachment.device = device;
			logcatAttachment.timestamp = report.getString(USER_CRASH_DATE);
			logs.add(logcatAttachment);
		}

		RequestBody requestData = new RequestBody(logs);
		Gson gson = new Gson();
		return gson.toJson(requestData);
	}

	private ExceptionModel getModelExceptionFromThrowable(Throwable t) {
		ExceptionModel topException = getModelException(t);
		ExceptionModel parentException = topException;
		for (Throwable cause = t.getCause(); cause != null; cause = cause.getCause()) {
			ExceptionModel exception = getModelException(cause);
			parentException.innerExceptions = Collections.singletonList(exception);
			parentException = exception;
		}
		return topException;
	}

	@NotNull
	private static ExceptionModel getModelException(Throwable t) {
		ExceptionModel exception = new ExceptionModel();
		exception.type = t.getClass().getName();
		exception.message = t.getMessage();
		exception.frames = getModelFramesFromStackTrace(t.getStackTrace());
		return exception;
	}

	private static List<StackFrame> getModelFramesFromStackTrace(StackTraceElement[] stackTrace) {
		int stackTraceLength = Math.min(stackTrace.length, 256);
		ArrayList<StackFrame> stackFrames = new ArrayList<>(stackTraceLength);
		for (int i = 0; i < stackTraceLength; i++) {
			StackTraceElement stackTraceElement = stackTrace[i];
			stackFrames.add(new StackFrame(stackTraceElement));
		}
		return stackFrames;
	}
}
