/*
 * Copyright (c) 2013, 2014 Chris Newland.
 * Licensed under https://github.com/AdoptOpenJDK/jitwatch/blob/master/LICENSE-BSD
 * Instructions: https://github.com/AdoptOpenJDK/jitwatch/wiki
 */
package org.adoptopenjdk.jitwatch.sandbox;

import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.S_DOT;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.adoptopenjdk.jitwatch.core.ILogParser;
import org.adoptopenjdk.jitwatch.core.JITWatchConfig;
import org.adoptopenjdk.jitwatch.core.JITWatchConstants;
import org.adoptopenjdk.jitwatch.core.JITWatchConfig.CompressedOops;
import org.adoptopenjdk.jitwatch.core.JITWatchConfig.TieredCompilation;
import org.adoptopenjdk.jitwatch.model.IMetaMember;
import org.adoptopenjdk.jitwatch.model.IReadOnlyJITDataModel;
import org.adoptopenjdk.jitwatch.model.MetaClass;
import org.adoptopenjdk.jitwatch.ui.sandbox.ISandboxStage;
import org.adoptopenjdk.jitwatch.util.FileUtil;
import org.adoptopenjdk.jitwatch.util.ParseUtil;
import org.adoptopenjdk.jitwatch.util.StringUtil;

public class Sandbox
{
	private ISandboxStage sandboxStage;

	public static final Path SANDBOX_DIR;
	public static final Path SANDBOX_SOURCE_DIR;
	public static final Path SANDBOX_CLASS_DIR;

	private static final String SANDBOX_LOGFILE = "sandbox.log";

	private File sandboxLogFile = new File(SANDBOX_DIR.toFile(), SANDBOX_LOGFILE);

	private ILogParser logParser;

	private String firstClassName;

	private String classContainingMain;

	static
	{
		String userDir = System.getProperty("user.dir");

		SANDBOX_DIR = Paths.get(userDir, "sandbox");
		SANDBOX_SOURCE_DIR = Paths.get(userDir, "sandbox", "sources");
		SANDBOX_CLASS_DIR = Paths.get(userDir, "sandbox", "classes");

		initialise();
	}

	private static void initialise()
	{
		File sandboxSources = SANDBOX_SOURCE_DIR.toFile();

		if (!sandboxSources.exists())
		{
			sandboxSources.mkdirs();

			if (sandboxSources.exists())
			{
				copyExamples();
			}
		}

		File sandboxClasses = SANDBOX_CLASS_DIR.toFile();

		if (!sandboxClasses.exists())
		{
			sandboxClasses.mkdirs();
		}
	}

	public void reset()
	{
		FileUtil.emptyDir(SANDBOX_DIR.toFile());
		initialise();
	}

	private static void copyExamples()
	{
		FileUtil.copyFilesToDir(new File("src/main/resources/examples"), SANDBOX_SOURCE_DIR.toFile());
	}

	public Sandbox(ILogParser parser, ISandboxStage logger)
	{
		this.logParser = parser;
		this.sandboxStage = logger;
	}

	public void runSandbox(List<String> sourceFiles) throws Exception
	{
		firstClassName = null;
		classContainingMain = null;

		List<File> compileList = new ArrayList<>();

		for (String source : sourceFiles)
		{
			File sourceFile = writeSourceFile(source);
			compileList.add(sourceFile);
		}

		sandboxStage.log("Compiling: " + StringUtil.listToString(compileList));

		ClassCompiler compiler = new ClassCompiler();

		boolean compiledOK = compiler.compile(compileList, SANDBOX_CLASS_DIR.toFile());

		sandboxStage.log("Compilation success: " + compiledOK);

		if (compiledOK)
		{
			if (classContainingMain != null)
			{
				ClassExecutor classExecutor = new ClassExecutor();

				boolean executionSuccess = executeTestLoad(classExecutor, logParser.getConfig().isSandboxIntelMode());

				sandboxStage.log("Execution success: " + executionSuccess);

				if (executionSuccess)
				{
					runJITWatch();
					showTriView();
				}
				else
				{
					sandboxStage.showError(classExecutor.getErrorStream());
				}
			}
			else
			{
				sandboxStage.log("No main method found");
			}
		}
		else
		{
			String compilationMessages = compiler.getCompilationMessages();
			sandboxStage.showError(compilationMessages);
		}
	}

	private File writeSourceFile(String source) throws IOException
	{
		String sourcePackage = ParseUtil.getPackageFromSource(source);

		String sourceClass = ParseUtil.getClassFromSource(source);

		StringBuilder fqNameSourceBuilder = new StringBuilder();

		if (sourcePackage.length() > 0)
		{
			fqNameSourceBuilder.append(sourcePackage).append(S_DOT);
		}

		fqNameSourceBuilder.append(sourceClass);

		String fqNameSource = fqNameSourceBuilder.toString();

		if (source.contains("public static void main(") || source.contains("public static void main ("))
		{
			classContainingMain = fqNameSource;
			sandboxStage.log("Found main method in " + classContainingMain);
		}

		if (firstClassName == null)
		{
			firstClassName = fqNameSource;
		}

		sandboxStage.log("Writing source file: " + fqNameSource + ".java");

		return FileUtil.writeSource(SANDBOX_SOURCE_DIR.toFile(), fqNameSource, source);
	}

	private boolean executeTestLoad(ClassExecutor classExecutor, boolean intelMode) throws Exception
	{
		List<String> classpath = new ArrayList<>();

		classpath.add(SANDBOX_CLASS_DIR.toString());

		classpath.addAll(logParser.getConfig().getClassLocations());

		List<String> options = new ArrayList<>();
		options.add("-XX:+UnlockDiagnosticVMOptions");
		options.add("-XX:+TraceClassLoading");
		options.add("-XX:+LogCompilation");
		options.add("-XX:LogFile=" + sandboxLogFile.getCanonicalPath());

		if (logParser.getConfig().isPrintAssembly())
		{
			options.add("-XX:+PrintAssembly");

			if (intelMode)
			{
				options.add("-XX:PrintAssemblyOptions=intel");
			}
		}

		TieredCompilation tieredMode = logParser.getConfig().getTieredCompilationMode();

		if (tieredMode == TieredCompilation.FORCE_TIERED)
		{
			options.add("-XX:+TieredCompilation");
		}
		else if (tieredMode == TieredCompilation.FORCE_NO_TIERED)
		{
			options.add("-XX:-TieredCompilation");
		}

		CompressedOops oopsMode = logParser.getConfig().getCompressedOopsMode();

		if (oopsMode == CompressedOops.FORCE_COMPRESSED)
		{
			options.add("-XX:+TieredCompilation");
		}
		else if (oopsMode == CompressedOops.FORCE_NO_COMPRESSED)
		{
			options.add("-XX:-TieredCompilation");
		}

		if (logParser.getConfig().getFreqInlineSize() != JITWatchConstants.DEFAULT_FREQ_INLINE_SIZE)
		{
			options.add("-XX:FreqInlineSize=" + logParser.getConfig().getFreqInlineSize());
		}

		if (logParser.getConfig().getMaxInlineSize() != JITWatchConstants.DEFAULT_MAX_INLINE_SIZE)
		{
			options.add("-XX:MaxInlineSize=" + logParser.getConfig().getMaxInlineSize());
		}

		if (logParser.getConfig().getCompilerThreshold() != JITWatchConstants.DEFAULT_COMPILER_THRESHOLD)
		{
			options.add("-XX:CompilerThreshold=" + logParser.getConfig().getCompilerThreshold());
		}

		sandboxStage.log("Executing: " + classContainingMain);
		sandboxStage.log("Classpath: " + StringUtil.listToString(classpath, File.pathSeparatorChar));
		sandboxStage.log("VM options: " + StringUtil.listToString(options));

		return classExecutor.execute(classContainingMain, classpath, options);
	}

	private void runJITWatch() throws IOException
	{
		JITWatchConfig config = logParser.getConfig();

		List<String> sourceLocations = new ArrayList<>(config.getSourceLocations());
		List<String> classLocations = new ArrayList<>(config.getClassLocations());

		String sandboxSourceDirString = SANDBOX_SOURCE_DIR.toString();
		String sandboxClassDirString = SANDBOX_CLASS_DIR.toString();
		
		boolean configChanged = false;

		if (!sourceLocations.contains(sandboxSourceDirString))
		{
			configChanged = true;
			sourceLocations.add(sandboxSourceDirString);
		}

		if (!classLocations.contains(sandboxClassDirString))
		{
			configChanged = true;
			classLocations.add(sandboxClassDirString);
		}

		File jdkSrcZip = JITWatchConfig.getJDKSourceZip();

		if (jdkSrcZip != null)
		{
			String jdkSourceZipString = jdkSrcZip.toPath().toString();

			if (!sourceLocations.contains(jdkSourceZipString))
			{
				configChanged = true;
				sourceLocations.add(jdkSourceZipString);
			}
		}

		config.setSourceLocations(sourceLocations);
		config.setClassLocations(classLocations);
		
		if (configChanged)
		{
			config.saveConfig();
		}
		
		logParser.reset();

		logParser.readLogFile(sandboxLogFile);

		sandboxStage.log("Parsing complete");
	}

	private void showTriView()
	{
		IReadOnlyJITDataModel model = logParser.getModel();

		sandboxStage.log("Looking up class: " + firstClassName);

		MetaClass metaClass = model.getPackageManager().getMetaClass(firstClassName);

		IMetaMember firstCompiled = null;

		if (metaClass != null)
		{
			sandboxStage.log("Found: " + metaClass.getFullyQualifiedName());

			sandboxStage.log("looking for compiled members");

			// select first compiled member if any
			List<IMetaMember> memberList = metaClass.getMetaMembers();

			for (IMetaMember mm : memberList)
			{
				if (mm.isCompiled())
				{
					firstCompiled = mm;
					break;
				}
			}
		}

		sandboxStage.openTriView(firstCompiled);
	}
}