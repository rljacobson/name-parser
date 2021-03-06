package org.gbif.nameparser;

import org.gbif.nameparser.utils.NamedThreadFactory;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 *
 */
public class InterruptibleCharSequenceTest {
  /**
   * Runs for about 10s
   */
  static class LongRegxJob implements Callable<Long> {
    private static Logger LOG = LoggerFactory.getLogger(LongRegxJob.class);
    private static final String TEMPLATE = "00000000000000000000000000000";
    private final CharSequence input;

    static LongRegxJob interruptable() {
      return new LongRegxJob(true);
    }
    static LongRegxJob regular() {
      return new LongRegxJob(false);
    }

    private LongRegxJob(boolean interruptable) {
      input = interruptable ? new InterruptibleCharSequence(TEMPLATE) : TEMPLATE;
    }

    @Override
    public Long call() throws Exception {
      final Pattern pattern = Pattern.compile("(0*)*A");

      long startTime = System.currentTimeMillis();
      Matcher matcher = pattern.matcher(input);
      matcher.find(); // runs for roughly a minute!
      long duration = System.currentTimeMillis() - startTime;
      LOG.info("Regex finished in {}ms", duration);
      return duration;
    }
  }

  @Test
  public void testRegexTimeout() throws InterruptedException {
    final String threadName = "regex-worker";
    final ExecutorService EXEC = new ThreadPoolExecutor(0, 20, 100L, TimeUnit.MILLISECONDS,
        new SynchronousQueue<Runnable>(),
        new NamedThreadFactory(threadName, Thread.NORM_PRIORITY, true),
        new ThreadPoolExecutor.CallerRunsPolicy());

    for (int x = 0; x<8; x++) {
      System.out.println("Executing task " + x);
      Future<Long> task = EXEC.submit(LongRegxJob.interruptable());
      try {
        Long duration = task.get(250, TimeUnit.MILLISECONDS);
        fail("Expected to timeout but parsed in " + duration + "ms");

      } catch (TimeoutException e) {
        // timeout happend, expected. Add more tasks
        System.out.println("Task " + x + " timed out as expected. Interrupt");
        task.cancel(true);

      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }

    //allow for some time to interrupt
    long sleep = 100;
    System.out.println("Wait for " + sleep + "ms");
    Thread.sleep(sleep);

    // now make sure the regex runner thread is dead!
    Set<Thread> threads = Thread.getAllStackTraces().keySet();
    for (Thread t : threads) {
      if (t.getName().startsWith(threadName)) {
        System.out.println(t.getName() + "  -->  " + t.getState());
      }
    }
    for (Thread t : threads) {
      if (t.getName().startsWith(threadName)) {
        assertFalse("Running executor thread detected", t.getState() == Thread.State.RUNNABLE);
      }
    }

    EXEC.shutdown();
  }

}