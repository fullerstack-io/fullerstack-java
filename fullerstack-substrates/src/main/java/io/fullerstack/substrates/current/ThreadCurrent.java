package io.fullerstack.substrates.current;

import io.humainary.substrates.api.Substrates;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.name.HierarchicalName;
import io.fullerstack.substrates.id.UuidIdentifier;

import java.util.Objects;

/**
 * Thread-based implementation of Current interface.
 * <p>
 * This implementation wraps the current thread information as a Current instance,
 * providing access to thread identity via Subject.
 * <p>
 * Each call to Thread.currentThread() creates a new Current wrapper, but the underlying
 * subject is cached per thread to maintain identity consistency.
 */
public final class ThreadCurrent implements Substrates.Current {

  private final Substrates.Subject < Substrates.Current > subject;

  // ThreadLocal cache to ensure consistent identity per thread
  private static final ThreadLocal < Substrates.Subject < Substrates.Current > > THREAD_SUBJECTS =
    ThreadLocal.withInitial ( ThreadCurrent::createSubjectForCurrentThread );

  /**
   * Creates a Current for the current thread.
   * Uses cached subject for the thread to maintain identity consistency.
   */
  private ThreadCurrent () {
    this.subject = THREAD_SUBJECTS.get ();
  }

  /**
   * Factory method to create a Current for the calling thread.
   *
   * @return A Current representing the current execution context
   */
  public static Substrates.Current of () {
    return new ThreadCurrent ();
  }

  @SuppressWarnings ( "unchecked" )
  private static Substrates.Subject < Substrates.Current > createSubjectForCurrentThread () {
    Thread thread = Thread.currentThread ();
    Substrates.Name name = HierarchicalName.of ( thread.getName () );

    // Create a deterministic UUID based on thread ID
    // Using thread ID as the most significant bits ensures different threads get different IDs
    long threadId = thread.threadId ();
    java.util.UUID uuid = new java.util.UUID ( threadId, 0L );
    Substrates.Id id = UuidIdentifier.of ( uuid );

    return new HierarchicalSubject < Substrates.Current > (
      id,
      name,
      null,  // No state for Current
      (Class < Substrates.Current >) (Class <?>) Substrates.Current.class
    );
  }

  @Override
  public Substrates.Subject < Substrates.Current > subject () {
    return subject;
  }

  @Override
  public boolean equals ( Object obj ) {
    if ( this == obj ) return true;
    if ( ! ( obj instanceof Substrates.Current ) ) return false;
    Substrates.Current other = (Substrates.Current) obj;
    return Objects.equals ( subject, other.subject () );
  }

  @Override
  public int hashCode () {
    return Objects.hash ( subject );
  }

  @Override
  public String toString () {
    return "Current[" + subject + "]";
  }
}
