package com.twitter.nexus.executor;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.internal.Preconditions;
import com.twitter.common.base.ExceptionalClosure;
import com.twitter.common.base.ExceptionalFunction;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.nexus.executor.HttpSignaler.SignalException;
import com.twitter.nexus.executor.ProcessKiller.KillCommand;
import com.twitter.nexus.executor.ProcessKiller.KillException;
import com.twitter.nexus.util.HdfsUtil;
import org.apache.hadoop.fs.FileSystem;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

/**
 * ExecutorModule
 *
 * @author Florian Leibert
 */
public class ExecutorModule extends AbstractModule {
  private final static java.util.logging.Logger LOG = Logger.getLogger(
      ExecutorModule.class.getName());
  private final ExecutorMain.TwitterExecutorOptions options;

  @Inject
  public ExecutorModule(ExecutorMain.TwitterExecutorOptions options) {
    this.options = Preconditions.checkNotNull(options);
  }

  @Override
  protected void configure() {
    bind(ExecutorCore.class).in(Singleton.class);
    bind(ExecutorHub.class).in(Singleton.class);
    bind(new TypeLiteral<ExceptionalFunction<File, Integer, FileToInt.FetchException>>() {})
        .to(FileToInt.class);
    bind(new TypeLiteral<
        ExceptionalFunction<Integer, Boolean, HealthChecker.HealthCheckException>>() {})
        .to(HealthChecker.class);
  }

  @Provides
  @Singleton
  public FileSystem provideFileSystem() throws IOException {
    return HdfsUtil.getHdfsConfiguration(options.hdfsConfig);
  }

  @Provides
  public ExceptionalFunction<FileCopyRequest, File, IOException> provideFileCopier(
      final FileSystem fileSystem) {
    return new ExceptionalFunction<FileCopyRequest, File, IOException>() {
      @Override public File apply(FileCopyRequest copy) throws IOException {
        LOG.info(String.format(
            "HDFS file %s -> local file %s", copy.getSourcePath(), copy.getDestPath()));
        // Thanks, Apache, for writing good code and just assuming that the path i give you has
        // a trailing slash.  Of course it makes sense to blindly append a file name to the path
        // i provide.
        String dirWithSlash = copy.getDestPath();
        if (!dirWithSlash.endsWith("/")) dirWithSlash += "/";

        return HdfsUtil.downloadFileFromHdfs(fileSystem, copy.getSourcePath(), dirWithSlash, true);
      }
    };
  }

  @Provides
  @Singleton
  public SocketManager provideSocketManager() {
    String[] portRange = options.managedPortRange.split("-");
    if (portRange.length != 2) {
      throw new IllegalArgumentException("Malformed managed port range value: "
                                         + options.managedPortRange);
    }

    return new SocketManagerImpl(Integer.parseInt(portRange[0]), Integer.parseInt(portRange[1]));
  }

  @Provides
  public ExceptionalFunction<String, List<String>, SignalException> provideHttpSignaler() {
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("HTTP-signaler-%d")
        .build();
    ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);

    return new HttpSignaler(executor,
        Amount.of((long) options.httpSignalTimeoutMs, Time.MILLISECONDS));
  }

  @Provides
  public ExceptionalClosure<KillCommand, KillException> provideProcessKiller(
      ExceptionalFunction<FileCopyRequest, File, IOException> fileCopier,
      ExceptionalFunction<String, List<String>, SignalException> httpSignaler) throws IOException {
    // TODO(wfarner): This should be handled by ProcessKiller - modules shouldn't be doing heavy
    //    work like this.
    // Fetch the killtree script.

    LOG.info("Fetching killtree script.");
    File killScript = fileCopier.apply(new FileCopyRequest(options.killTreeHdfsPath,
        options.taskRootDir.getAbsolutePath()));

    return new ProcessKiller(httpSignaler, killScript,
        Amount.of((long) options.killEscalationMs, Time.MILLISECONDS));
  }
}
