/*
 * Copyright (c) 2018 Tada AB and other contributors, as listed below.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the The BSD 3-Clause License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Contributors:
 *   Chapman Flack
 */
package org.postgresql.pljava.internal;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.io.IOException;

import java.nio.ByteBuffer;

import java.sql.SQLException;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Interface that wraps a PostgreSQL native variable-length ("varlena") datum;
 * implementing classes present an existing one to Java as a readable
 * {@code InputStream}, or allow a new one to be constructed by presenting a
 * writable {@code OutputStream}.
 *<p>
 * Common to both is a method {@link #adopt adopt()}, allowing native
 * code to reassert control over the varlena (for the writable variety, after
 * Java code has written and closed it), after which it is no longer accessible
 * from Java.
 */
public interface VarlenaWrapper extends Closeable
{
	/**
	 * Return the varlena address to native code and dissociate the varlena
	 * from Java.
	 * @param cookie Capability held by native code.
	 */
	long adopt(DualState.Key cookie) throws SQLException;

	/**
	 * Return a string describing this object in a way useful for debugging,
	 * prefixed with the name (abbreviated for comfort) of the class of the
	 * object passed in (the normal Java {@code toString()} method should pass
	 * {@code this}).
	 *<p>
	 * Subclasses or consumers are encouraged to call this method and append
	 * further details specific to the subclass or consumer. The convention
	 * should be that the recursion will stop at some class that will actually
	 * construct the abbreviated class name of {@code o} and use it to prefix
	 * the returned value.
	 * @param o An object whose class name (possibly abbreviated) should be used
	 * to prefix the returned string.
	 * @return Description of this object.
	 */
	String toString(Object o);



	/**
	 * A class by which Java reads the content of a varlena as an InputStream.
	 *
	 * Associated with a {@code ResourceOwner} to bound the lifetime of
	 * the native reference; the chosen resource owner must be one that will be
	 * released no later than the memory context containing the varlena.
	 */
	public static class Input
	extends ByteBufferInputStream implements VarlenaWrapper
	{
		private long m_parkedSize;
		private long m_bufferSize;

		/**
		 * Construct a {@code VarlenaWrapper.Input}.
		 * @param cookie Capability held by native code.
		 * @param resourceOwner Resource owner whose release will indicate that the
		 * underlying varlena is no longer valid.
		 * @param context Memory context in which the varlena is allocated.
		 * @param snapshot A snapshot that has been registered in case the
		 * parked varlena is TOASTed on disk, to keep the toast tuples from
		 * being vacuumed away.
		 * @param varlenaPtr Pointer value to the underlying varlena, to be
		 * {@code pfree}d when Java code closes or reclaims this object.
		 * @param parkedSize Size occupied by this datum in memory while it is
		 * "parked", that is, before the first call to a reading method.
		 * @param bufferSize Size that is or will be occupied by the detoasted
		 * content once a reading method has been called.
		 * @param buf Readable direct {@code ByteBuffer} constructed over the
		 * varlena's data bytes.
		 */
		private Input(DualState.Key cookie, long resourceOwner,
			long context, long snapshot, long varlenaPtr,
			long parkedSize, long bufferSize, ByteBuffer buf)
		{
			m_parkedSize = parkedSize;
			m_bufferSize = bufferSize;
			m_state = new State(
				cookie, this, resourceOwner,
				context, snapshot, varlenaPtr, buf);
		}

		/**
		 * Apply a {@code Verifier} to the input data.
		 *<p>
		 * This should only be necessary if the input wrapper is being used
		 * directly as an output item, and needs verification that it conforms
		 * to the format of the target type.
		 *<p>
		 * The current position must be at the beginning of the stream. The
		 * verifier must leave it at the end to confirm the entire stream was
		 * examined. There should be no need to reset the position here, as the
		 * only anticipated use is during an {@code adopt}, and the native code
		 * will only care about the varlena's address.
		 */
		public void verify(Verifier v) throws SQLException
		{
			try
			{
				ByteBuffer buf = buffer();
				if ( 0 != buf.position() )
					throw new SQLException(
						"Variable-length input data to be verified " +
						" not positioned at start",
						"55000");
				InputStream dontCloseMe = new FilterInputStream(this)
				{
					@Override
					public void close() throws IOException { }
				};
				v.verify(dontCloseMe);
				if ( 0 != buf.remaining() )
					throw new SQLException("Verifier finished prematurely");
			}
			catch ( SQLException sqe )
			{
				throw sqe;
			}
			catch ( RuntimeException rte )
			{
				throw rte;
			}
			catch ( Exception e )
			{
				throw new SQLException(
					"Error verifying variable-length input data, " +
					"not otherwise provided for", "XX000", e);
			}
		}

		@Override
		protected ByteBuffer buffer() throws IOException
		{
			if ( ! m_open )
				throw new IOException("Read from closed VarlenaWrapper");
			try
			{
				return ((State)m_state).buffer();
			}
			catch ( SQLException sqe )
			{
				throw new IOException("Read from varlena failed", sqe);
			}
		}

		@Override
		public void close() throws IOException
		{
			synchronized ( m_state )
			{
				super.close();
				((State)m_state).enqueue();
			}
		}

		@Override
		public long adopt(DualState.Key cookie) throws SQLException
		{
			synchronized ( m_state )
			{
				if ( ! m_open )
					throw new SQLException(
						"Cannot adopt VarlenaWrapper.Input after it is closed",
						"55000");
				return ((State)m_state).adopt(cookie);
			}
		}

		@Override
		public String toString()
		{
			return toString(this);
		}
		
		@Override
		public String toString(Object o)
		{
			return String.format("%s parked:%d buffer:%d %s",
				((State)m_state).toString(o), m_parkedSize, m_bufferSize,
				m_open ? "open" : "closed");
		}



		private static class State
		extends DualState.SingleMemContextDelete<Input>
		{
			private ByteBuffer m_buf;
			private long m_snapshot;
			private long m_varlena;

			private State(
				DualState.Key cookie, Input vr, long resourceOwner,
				long memContext, long snapshot, long varlenaPtr, ByteBuffer buf)
			{
				super(cookie, vr, resourceOwner, memContext);
				m_snapshot = snapshot;
				m_varlena = varlenaPtr;
				m_buf = null == buf ? buf : buf.asReadOnlyBuffer();
			}

			private ByteBuffer buffer() throws SQLException
			{
				long ctx = getMemoryContext();
				if ( null == m_buf )
				{
					synchronized ( Backend.THREADLOCK )
					{
						m_buf = _detoast(
							m_varlena, ctx, m_snapshot, m_resourceOwner)
								.asReadOnlyBuffer();
						m_snapshot = 0;
					}
				}
				return m_buf;
			}

			private long adopt(DualState.Key cookie) throws SQLException
			{
				checkCookie(cookie);
				long ctx = getMemoryContext();
				if ( 0 != m_snapshot ) /* fetch now, before snapshot released */
				{
					synchronized ( Backend.THREADLOCK )
					{
						m_varlena = _fetch(m_varlena, ctx);
					}
				}
				long varlena = m_varlena;
				nativeStateReleased();
				return varlena;
			}

			@Override
			protected void nativeStateReleased()
			{
				synchronized ( Backend.THREADLOCK )
				{
					super.nativeStateReleased();
					/*
					 * You might not expect to have to explicitly unregister a
					 * snapshot from the resource owner that is at this very
					 * moment being released, and will happily unregister the
					 * snapshot itself in the course of so doing. Ah, but it
					 * also happily logs a warning when it does that, so we need
					 * to have our toys picked up before it gets the chance.
					 */
					if ( 0 != m_snapshot )
						_unregisterSnapshot(m_snapshot, m_resourceOwner);
					m_snapshot = 0;
				}
				m_buf = null;
			}

			@Override
			protected void javaStateReleased()
			{
				synchronized ( Backend.THREADLOCK )
				{
					super.javaStateReleased();
					if ( 0 != m_snapshot )
						_unregisterSnapshot(m_snapshot, m_resourceOwner);
					m_snapshot = 0;
				}
				m_buf = null;
			}

			@Override
			public String toString(Object o)
			{
				return String.format("%s snap:%x varlena:%x %s",
					super.toString(o), m_snapshot, m_varlena,
					String.valueOf(m_buf).replace("java.nio.", ""));
			}

			/**
			 * Unregister a snapshot we've been holding.
			 */
			private native void
				_unregisterSnapshot(long snap, long resOwner);

			/**
			 * Detoast the parked value; called when a method needing to read it
			 * has been invoked.
			 *<p>
			 * Detoast the passed {@code varlena} into the same
			 * {@code memContext}, {@code pfree} the original, update the
			 * {@code m_varlena} instance field to point to the detoasted copy,
			 * and return a direct byte buffer that windows it.
			 *<p>
			 * If {@code snapshot} is nonzero, unregister the snapshot from the
			 * resource owner. The caller may rely on this happening, and
			 * confidently set {@code m_snapshot} to zero after this call.
			 */
			private native ByteBuffer _detoast(
				long varlena, long memContext, long snapshot, long resOwner);

			/**
			 * Merely fetch a parked value, when it does not need to be fully
			 * detoasted and readable, but simply retrieved from its TOAST rows
			 * before loss of the snapshot that may be protecting them from
			 * VACUUM. The original value is {@code pfree}d.
			 *<p>
			 * The result may still have an 'extended' (for example, compressed)
			 * form.
			 */
			private native long _fetch(long varlena, long memContext);
		}
	}

	/**
	 * A class by which Java writes the content of a varlena as an OutputStream.
	 *
	 * Associated with a {@code ResourceOwner} to bound the lifetime of
	 * the native reference; the chosen resource owner must be one that will be
	 * released no later than the memory context containing the varlena.
	 */
	public class Output extends OutputStream implements VarlenaWrapper
	{
		private State m_state;
		private boolean m_open = true;

		/**
		 * Construct a {@code VarlenaWrapper.Output}.
		 * @param cookie Capability held by native code.
		 * @param resourceOwner Resource owner whose release will indicate that
		 * the underlying varlena is no longer valid.
		 * @param context Pointer to memory context containing the underlying
		 * varlena; subject to {@code MemoryContextDelete} if Java code frees or
		 * reclaims this object.
		 * @param varlenaPtr Pointer value to the underlying varlena.
		 * @param buf Writable direct {@code ByteBuffer} constructed over (an
		 * initial region of) the varlena's data bytes.
		 */
		private Output(DualState.Key cookie, long resourceOwner,
			long context, long varlenaPtr, ByteBuffer buf)
		{
			m_state = new State(
				cookie, this, resourceOwner, context, varlenaPtr, buf);
		}

		/**
		 * Set the {@link Verifier Verifier} to be used on content written to
		 * this varlena.
		 *<p>
		 * A verifier must be set, either to {@link Verifier.NoOp NoOp} or a
		 * datatype-specific subclass of {@link Verifier.Base Base}, before
		 * writing can succeed.
		 *<p>
		 * On construction, no verifier is set, so the datatype-specific code
		 * can determine whether the {@code NoOp} or a specific verifier will be
		 * needed. This method can only be called once, so that this class could
		 * then be exposed to client code as an {@code OutputStream} without
		 * allowing the verifier to be changed.
		 */
		public void setVerifier(Verifier v) throws IOException
		{
			if ( ! m_open )
				throw new IOException(
					"I/O operation on closed VarlenaWrapper.Output");
			m_state.setVerifier(v);
		}

		/**
		 * Return a ByteBuffer to write into.
		 *<p>
		 * It will be the existing buffer if it has any remaining capacity to
		 * write into (even if it is less than desiredCapacity), otherwise a new
		 * buffer allocated with desiredCapacity as a hint (it may still be
		 * smaller than the hint, or larger). Call with desiredCapacity zero to
		 * indicate that writing is finished and make the varlena available for
		 * native code to adopt.
		 */
		private ByteBuffer buf(int desiredCapacity) throws IOException
		{
			if ( ! m_open )
				throw new IOException("Write on closed VarlenaWrapper.Output");
			try
			{
				return m_state.buffer(desiredCapacity);
			}
			catch ( SQLException sqe )
			{
				throw new IOException("Write on varlena failed", sqe);
			}
		}

		@Override
		public void write(int b) throws IOException
		{
			synchronized ( m_state )
			{
				ByteBuffer dst = buf(1);
				dst.put((byte)(b & 0xff));
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException
		{
			synchronized ( m_state )
			{
				while ( 0 < len )
				{
					ByteBuffer dst = buf(len);
					int can = dst.remaining();
					if ( can > len )
						can = len;
					dst.put(b, off, can);
					off += can;
					len -= can;
				}
			}
		}

		@Override
		public void close() throws IOException
		{
			synchronized ( m_state )
			{
				if ( ! m_open )
					return;
				buf(0);
				m_open = false;
			}
			/*
			 * Outside of synchronized block to avoid deadlock with verifier.
			 */
			m_state.verify();
		}

		/**
		 * Actually free a {@code VarlenaWrapper.Output}.
		 *<p>
		 * {@code close()} does not do so, because the typical use of this class
		 * is to write to an instance, close it, then let some native code adopt
		 * it. If it turns out one won't be adopted and must be freed, use this
		 * method.
		 */
		public void free() throws IOException
		{
			close();
			m_state.javaStateReleased();
		}

		@Override
		public long adopt(DualState.Key cookie) throws SQLException
		{
			synchronized ( m_state )
			{
				if ( m_open )
					throw new SQLException(
						"Writing of VarlenaWrapper.Output not yet complete",
						"55000");
				return m_state.adopt(cookie);
			}
		}

		@Override
		public String toString()
		{
			return toString(this);
		}

		@Override
		public String toString(Object o)
		{
			return String.format("%s %s", m_state.toString(o),
				m_open ? "open" : "closed");
		}



		private static class State
		extends DualState.SingleMemContextDelete<Output>
		{
			private ByteBuffer m_buf;
			private long m_varlena;
			private Verifier m_verifier;

			private State(
				DualState.Key cookie, Output vr,
				long resourceOwner,	long memContext, long varlenaPtr,
				ByteBuffer buf)
			{
				super(cookie, vr, resourceOwner, memContext);
				m_varlena = varlenaPtr;
				m_buf = buf;
			}

			private ByteBuffer buffer(int desiredCapacity) throws SQLException
			{
				assertNativeStateIsValid();
				if ( 0 < m_buf.remaining()  &&  0 < desiredCapacity )
					return m_buf;
				ByteBuffer filledBuf = m_buf;
				synchronized ( Backend.THREADLOCK )
				{
					m_buf =	_nextBuffer(m_varlena, m_buf.position(),
								desiredCapacity);
				}
				m_verifier.update(this, filledBuf);
				if ( 0 == desiredCapacity )
					m_verifier.update(MarkableSequenceInputStream.NO_MORE);
				return m_buf;
			}

			private long adopt(DualState.Key cookie) throws SQLException
			{
				checkCookie(cookie);
				assertNativeStateIsValid();
				long varlena = m_varlena;
				nativeStateReleased();
				return varlena;
			}

			private void setVerifier(Verifier v)
			{
				if ( null != m_verifier )
					throw new IllegalStateException(
						"setVerifier when already set");
				if ( null == v )
					throw new NullPointerException("Null Verifier parameter");
				m_verifier = v.schedule();
			}

			private void cancelVerifier()
			{
				try
				{
					m_verifier.cancel();
				}
				catch ( Exception e )
				{
				}
			}

			/*
			 * Caller must NOT be in synchronized block, to make sure verifier
			 * thread can complete.
			 */
			private void verify() throws IOException // because called in close
			{
				try
				{
					m_verifier.finish();
				}
				catch ( SQLException e )
				{
					throw new IOException(
						"Variable-length PostgreSQL data written failed " +
						"verification", e);
				}
			}

			@Override
			public String toString(Object o)
			{
				return String.format("%s varlena:%x %s",
					super.toString(o), m_varlena,
					String.valueOf(m_buf).replace("java.nio.", ""));
			}

			@Override
			protected void nativeStateReleased()
			{
				m_buf = null;
				cancelVerifier();
				super.nativeStateReleased();
			}

			@Override
			protected void javaStateReleased()
			{
				m_buf = null;
				cancelVerifier();
				super.javaStateReleased();
			}

			private native ByteBuffer _nextBuffer(
				long varlenaPtr, int currentBufPosition, int desiredCapacity);
		}
	}

	/**
	 * A {@code Verifier} verifies the proper form of content written to a
	 * {@code VarlenaWrapper.Output}.
	 *<p>
	 * This is necessary only when the correctness of the written stream may be
	 * doubtful, as when an API spec requires exposing a method for client code
	 * to write arbitrary bytes. If a type implementation exposes only
	 * type-appropriate operations to client code, and always controls the byte
	 * stream written to the varlena, the {@code NoOp} verifier can be used.
	 *<p>
	 * {@code Verifier} itself cannot be instantiated or extended, except by its
	 * two immediate subclasses, {@link NoOp NoOp} and {@link Base Base}.
	 * Type-specific verifiers must extend {@code Base}. Exactly one instance of
	 * {@code NoOp}, {@link NoOp#INSTANCE NoOp.INSTANCE}, exists.
	 *<p>
	 * A type-specific verifier must supply a {@link #verify} method that reads
	 * its input stream argument (which may be assumed to support
	 * {@link InputStream#mark mark} and {@link InputStream#reset reset}
	 * efficiently), and complete normally if the full stream is a complete and
	 * well-formed representation of the type. Otherwise, it must throw an
	 * exception.
	 *<p>
	 * In use, a verifier is instantiated and {@link #schedule schedule()}d,
	 * which sets the {@code verify} method running in a separate thread.
	 * <em>The {@code verify} method must not interact with PostgreSQL.</em>
	 * The varlena wrapper code then passes buffers to it via {@link #update
	 * update()} as they are filled. A final call to {@link #finish}, in the
	 * thread interacting with PostgreSQL, waits for the verify task to
	 * complete and then rethrows the exception, if it threw one. It is possible
	 * to {@link #cancel} a {@code Verifier}.
	 *<p>
	 * As an optimization, all those methods are no-ops in the {@code NoOp}
	 * class; no other thread is used, and no work is done. The {@code Base}
	 * class, unextended, also serves as a verifier that accepts anything, but
	 * goes through all the motions to do it.
	 */
	public static abstract class Verifier implements Callable<Void>
	{
		private final BlockingQueue<InputStream> m_queue;
		private final AtomicReference<CountDownLatch> m_latch;
		private volatile Future<Void> m_future;

		/*
		 * The design of Java's FutureTask strikes me as bizarre. One might
		 * think that the most natural way to use it for a task that does a
		 * certain thing and returns a result would be to extend it, override
		 * a particular method to do that thing, and submit it, ending up with
		 * one object that serves both as the task to be run and the Future.
		 * And that seems to be the exact design approach that its API
		 * /precludes/, because its only available constructors require passing
		 * a Runnable or Callable /that is some other object/.
		 *
		 * Can the constructor create a Callable that simply calls back to the
		 * verify method of this object, and pass that callable to the
		 * FutureTask constructor? No, because referring to 'this' before the
		 * supertype constructor has been called is a compile-time error.
		 *
		 * I really want one Verifier object that has all of: the verify()
		 * method being run in the executor, the update() method used to feed it
		 * stuff, and the Future-inspired methods for dealing with its status
		 * and result. And the only way I am seeing to get there is to have it
		 * submit() itself (in the schedule method) and then hold a reference to
		 * the Future created for it in that operation. Then it can have some
		 * Future-inspired methods that are more or less proxies to the methods
		 * on the Future itself, but then those have to deal with the chance
		 * that the Future reference hasn't been stored yet, so another whole
		 * synchronization puzzle crops up around the convenient synchronization
		 * tool. :(
		 *
		 * So, this method returns the Future, if we have it yet, or throws an
		 * IllegalStateException if we shouldn't have it yet because schedule()
		 * hasn't been called (or hasn't installed the latch yet; it's races all
		 * the way down), or waits for the latch and /then/ returns the Future.
		 */
		private Future<Void> future() throws SQLException
		{
			Future<Void> f = m_future;
			if ( null != f )
				return f;
			CountDownLatch cll = m_latch.get();
			if ( null == cll )
				throw new IllegalStateException("Verifier not yet scheduled");
			try
			{
				cll.await();
			}
			catch ( InterruptedException e )
			{
				throw new SQLException("Waiting thread interrupted", e);
			}
			return m_future;
		}

		/*
		 * Private constructor. The nested class NoOp can call it passing nulls.
		 */
		private Verifier(
			BlockingQueue<InputStream> queue,
			AtomicReference<CountDownLatch> latch)
		{
			m_queue = queue;
			m_latch = latch;
		}

		/*
		 * The nested class Base can call this one. Otherwise it's private,
		 * so no other direct subclasses are possible.
		 */
		private Verifier()
		{
			this(new LinkedBlockingQueue<InputStream>(),
				 new AtomicReference<CountDownLatch>());
		}

		protected void verify(InputStream is) throws Exception
		{
			do
			{
				is.skip(Long.MAX_VALUE);
			}
			while ( -1 != is.read() );
		}

		@Override
		public final Void call() throws Exception
		{
			InputStream is = null;
			try
			{
				is = new MarkableSequenceInputStream(m_queue);
				verify(is);
			}
			finally
			{
				if ( null != is )
					is.close();
			}
			return null;
		}

		/**
		 * A Verifier that accepts any content, cheaply.
		 */
		public static final class NoOp extends Verifier
		{
			private NoOp() { super(null, null); }

			public static final Verifier INSTANCE = new NoOp();

			public Verifier schedule()
			{
				return this;
			}

			public void update(InputStream is) throws SQLException
			{
			}

			public void update(Output.State state, ByteBuffer bb)
			throws SQLException
			{
			}

			public void finish() throws SQLException
			{
			}

			public void cancel() throws SQLException
			{
			}
		}

		/**
		 * Verifier to be extended to verify byte streams for specific types.
		 *<p>
		 * A subclass should override {@link verify} with a method that reads
		 * the InputStream and throws an exception unless the entire stream was
		 * successfully read and represented a well-formed instance of the type.
		 */
		public static class Base extends Verifier
		{
			protected Base() { }

			@Override
			public final Verifier schedule()
			{
				return super.schedule();
			}

			@Override
			public final void update(InputStream is) throws SQLException
			{
				super.update(is);
			}

			public final void update(Output.State state, ByteBuffer bb)
			throws SQLException
			{
				super.update(state, bb);
			}

			@Override
			public final void finish() throws SQLException
			{
				super.finish();
			}

			@Override
			public final void cancel() throws SQLException
			{
				super.cancel();
			}
		}

		/**
		 * Set up the {@link #verify verify} method to be executed
		 * in another thread.
		 * @return This {@code Verifier} object.
		 */
		public Verifier schedule()
		{
			CountDownLatch cll = new CountDownLatch(1);
			if ( m_latch.compareAndSet(null, cll) )
			{
				m_future = LazyExecutorService.INSTANCE.submit(this);
				cll.countDown();
			}
			return this;
		}

		/**
		 * Send the next {@code InputStream} of content to be verified.
		 *<p>
		 * It is assumed, but <em>not checked here</em>, that any
		 * {@code InputStream} supplied to this method supports
		 * {@link InputStream#mark mark} and {@link InputStream#reset reset}
		 * efficiently.
		 *<p>
		 * If the verifier has already thrown an exception, it will be rethrown
		 * here in the current thread.
		 * @param is InputStream representing the next range of bytes to be
		 * verified.
		 * @throws SQLException if a verification error has already been
		 * detected, the verifier has been cancelled, etc.
		 */
		public void update(InputStream is) throws SQLException
		{
			Future<Void> f = future();
			if ( f.isDone() )
			{
				finish();
				throw new SQLException("Verifier finished prematurely");
			}
			try
			{
				m_queue.put(is);
			}
			catch ( InterruptedException e )
			{
				f.cancel(true);
				throw (CancellationException)
					new CancellationException("Waiting thread interrupted")
						.initCause(e);
			}
		}

		/**
		 * Convenience method that calls {@link ByteBuffer#flip flip()} on a
		 * byte buffer, wraps it in a {@link BufferWrapper BufferWrapper}, and
		 * passes it to {@link update(InputStream)}.
		 *<p>
		 * Note that the {@link NoOp NoOp} version of this method does none of
		 * that; in particular, the byte buffer will not have been flipped. This
		 * should not be a problem, as the thread passing the buffer to this
		 * method had better make no further use of it anyway.
		 * @param state The state object protecting the native memory.
		 * @param bb Byte buffer containing next range of content to verify.
		 * @throws SQLException if a verification error has already been
		 * detected, the verifier has been cancelled, etc.
		 */
		public void update(Output.State state, ByteBuffer bb)
		throws SQLException
		{
			bb.flip();
			update(new BufferWrapper(state, bb));
		}

		/**
		 * Cancel this verifier.
		 */
		public void cancel() throws SQLException
		{
			Future<Void> f = future();
			f.cancel(true);
		}

		/**
		 * Wait for the verify task and rethrow any exception it might
		 * have thrown.
		 * @throws SQLException any exception thrown by the verify method, or
		 * for unexpected conditions such as interruption while waiting.
		 */
		public void finish() throws SQLException
		{
			Future<Void> f = future();

			try
			{
				f.get();
			}
			catch ( InterruptedException inte )
			{
				f.cancel(true);
				throw (CancellationException)
					new CancellationException("Waiting thread interrupted")
						.initCause(inte);
			}
			catch ( ExecutionException exce )
			{
				Throwable t = exce.getCause();
				if ( t instanceof SQLException )
					throw (SQLException) t;
				if ( t instanceof RuntimeException )
					throw (RuntimeException) t;
				throw new SQLException(
					"Exception verifying variable-length data, not " +
					"otherwise provided for", "XX000", exce);
			}

			if ( ! m_queue.isEmpty() )
				throw new SQLException("Verifier finished prematurely");
		}

		/**
		 * Lazy holder for a singleton instance of a thread-pool
		 * {@link ExecutorService}.
		 *<p>
		 * If it ever happens later that other PL/Java components could have use
		 * for a thread pool, this could certainly be moved out of
		 * {@code VarlenaWrapper} to a more common place.
		 */
		static class LazyExecutorService
		{
			static final ExecutorService INSTANCE;

			static
			{
				final ThreadFactory dflttf = Executors.defaultThreadFactory();
				ThreadFactory daemtf = new ThreadFactory()
				{
					@Override
					public Thread newThread(Runnable r)
					{
						Thread t = dflttf.newThread(r);
						if ( null != t )
						{
							t.setDaemon(true);
							t.setName(
								"varlenaVerify-" + t.getName().substring(5));
						}
						return t;
					}
				};
				INSTANCE = Executors.newCachedThreadPool(daemtf);
			}
		}

		/**
		 * {@link ByteBufferInputStream ByteBufferInputStream} subclass that
		 * wraps a {@code ByteBuffer} and the {@link Output.State Output.State}
		 * that protects it.
		 */
		static class BufferWrapper extends ByteBufferInputStream
		{
			private ByteBuffer m_buf;

			BufferWrapper(Output.State state, ByteBuffer buf)
			{
				m_state = state;
				m_buf = buf;
			}

			@Override
			protected ByteBuffer buffer() throws IOException
			{
				if ( ! m_open )
					throw new IOException(
						"I/O operation on closed VarlenaWrapper.Verifier");
				try
				{
					((Output.State)m_state).assertNativeStateIsValid();
				}
				catch ( SQLException e )
				{
					throw new IOException(e.getMessage(), e);
				}
				return m_buf;
			}
		}
	}
}
