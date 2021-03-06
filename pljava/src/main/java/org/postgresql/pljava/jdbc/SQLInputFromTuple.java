/*
 * Copyright (c) 2004-2018 Tada AB and other contributors, as listed below.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the The BSD 3-Clause License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Contributors:
 *   Tada AB
 *   Chapman Flack
 */
package org.postgresql.pljava.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInput;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;

import org.postgresql.pljava.internal.Backend;
import org.postgresql.pljava.internal.JavaWrapper;
import org.postgresql.pljava.internal.TupleDesc;

/**
 * Implements the {@code SQLInput} interface for a user-defined type (UDT)
 * implemented in Java, for the case where a composite type in PostgreSQL is
 * used as the UDT's representation, so it can be accessed as a PG tuple.
 *
 * @author Thomas Hallgren
 */
public class SQLInputFromTuple extends JavaWrapper implements SQLInput
{
	private int m_index;
	private final TupleDesc m_tupleDesc;
	private boolean m_wasNull;

	/**
	 * Construct an instance, given the (native) pointer to a PG
	 * {@code HeapTupleHeader}, as well as the TupleDesc (Java object this time)
	 * describing its structure.
	 */
	public SQLInputFromTuple(long heapTupleHeaderPointer, TupleDesc tupleDesc)
	throws SQLException
	{
		super(heapTupleHeaderPointer);
		m_tupleDesc = tupleDesc;
		m_index   = 0;
		m_wasNull = false;
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public Array readArray() throws SQLException
	{
		return (Array)this.readValue(Array.class);
	}

	/**
	 * Implemented over {@link #readClob}.
	 */
	@Override
	public InputStream readAsciiStream() throws SQLException
	{
		Clob c = this.readClob();
		return (c == null) ? null : c.getAsciiStream();
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public BigDecimal readBigDecimal() throws SQLException
	{
		return (BigDecimal)this.readValue(BigDecimal.class);
	}

	/**
	 * Implemented over {@link #readBlob}.
	 */
	@Override
	public InputStream readBinaryStream() throws SQLException
	{
		Blob b = this.readBlob();
		return (b == null) ? null : b.getBinaryStream();
	}

	/**
	 * Implemented over {@link #readBytes}.
	 */
	@Override
	public Blob readBlob() throws SQLException
	{
		byte[] bytes = this.readBytes();
		return (bytes == null) ? null :  new BlobValue(bytes);
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public boolean readBoolean() throws SQLException
	{
		Boolean b = (Boolean)this.readValue(Boolean.class);
		return (b == null) ? false : b.booleanValue();
	}

	/**
	 * Implemented over {@link #readNumber}.
	 */
	@Override
	public byte readByte() throws SQLException
	{
		Number b = this.readNumber(byte.class);
		return (b == null) ? 0 : b.byteValue();
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public byte[] readBytes() throws SQLException
	{
		return (byte[])this.readValue(byte[].class);
	}

	/**
	 * Implemented over {@link #readClob}.
	 */
	public Reader readCharacterStream() throws SQLException
	{
		Clob c = this.readClob();
		return (c == null) ? null : c.getCharacterStream();
	}

	/**
	 * Implemented over {@link #readString}.
	 */
	public Clob readClob() throws SQLException
	{
		String str = this.readString();
		return (str == null) ? null :  new ClobValue(str);
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public Date readDate() throws SQLException
	{
		return (Date)this.readValue(Date.class);
	}

	/**
	 * Implemented over {@link #readNumber}.
	 */
	@Override
	public double readDouble() throws SQLException
	{
		Number d = this.readNumber(double.class);
		return (d == null) ? 0 : d.doubleValue();
	}

	/**
	 * Implemented over {@link #readNumber}.
	 */
	@Override
	public float readFloat() throws SQLException
	{
		Number f = this.readNumber(float.class);
		return (f == null) ? 0 : f.floatValue();
	}

	/**
	 * Implemented over {@link #readNumber}.
	 */
	@Override
	public int readInt() throws SQLException
	{
		Number i = this.readNumber(int.class);
		return (i == null) ? 0 : i.intValue();
	}

	/**
	 * Implemented over {@link #readNumber}.
	 */
	@Override
	public long readLong() throws SQLException
	{
		Number l = this.readNumber(long.class);
		return (l == null) ? 0 : l.longValue();
	}

	@Override
	public Object readObject() throws SQLException
	{
		if(m_index < m_tupleDesc.size())
		{
			Object v;
			synchronized(Backend.THREADLOCK)
			{
				v = _getObject(
					this.getNativePointer(), m_tupleDesc.getNativePointer(),
					++m_index, null);
			}
			m_wasNull = v == null;
			return v;
		}
		throw new SQLException("Tuple has no more columns");
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public Ref readRef() throws SQLException
	{
		return (Ref)this.readValue(Ref.class);
	}

	/**
	 * Implemented over {@link #readNumber}.
	 */
	@Override
	public short readShort() throws SQLException
	{
		Number s = this.readNumber(short.class);
		return (s == null) ? 0 : s.shortValue();
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public String readString() throws SQLException
	{
		return (String)this.readValue(String.class);
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public Time readTime() throws SQLException
	{
		return (Time)this.readValue(Time.class);
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public Timestamp readTimestamp() throws SQLException
	{
		return (Timestamp)this.readValue(Timestamp.class);
	}

	/**
	 * Implemented over {@link #readValue}.
	 */
	@Override
	public URL readURL() throws SQLException
	{
		return (URL)this.readValue(URL.class);
	}

	@Override
	public boolean wasNull() throws SQLException
	{
		return m_wasNull;
	}

	// ************************************************************
	// Implementation of JDBC 4 methods. Methods go here if they
	// don't throw SQLFeatureNotSupportedException; they can be
	// considered implemented even if they do nothing useful, as
	// long as that's an allowed behavior by the JDBC spec.
	// ************************************************************

	@Override
	public SQLXML readSQLXML()
		throws SQLException
	{
		return this.readObject(SQLXML.class);
	}

	// ************************************************************
	// Non-implementation of JDBC 4 methods.
	// ************************************************************

	/** Not yet implemented. */
	@Override
	public RowId readRowId()
                throws SQLException
	{
		throw new SQLFeatureNotSupportedException
			( this.getClass()
			  + ".readRowId() not implemented yet.",
			  "0A000" );
	}

	/** Not yet implemented. */
	@Override
	public String readNString()
		throws SQLException
	{
		throw new SQLFeatureNotSupportedException
			( this.getClass()
			  + ".readNString() not implemented yet.",
			  "0A000" );
		
	}
	
	/** Not yet implemented. */
	@Override
	public NClob readNClob()
	       throws SQLException
	{
		throw new SQLFeatureNotSupportedException
			( this.getClass()
			  + ".readNClob() not implemented yet.",
		  "0A000" );
		
	}

	// ************************************************************
	// Implementation of JDBC 4.2 method.
	// Add @Override here once Java back horizon advances to 8.
	// ************************************************************

	public <T> T readObject(Class<T> type) throws SQLException
	{
		if(m_index < m_tupleDesc.size())
		{
			Object v;
			synchronized(Backend.THREADLOCK)
			{
				v = _getObject(
					this.getNativePointer(), m_tupleDesc.getNativePointer(),
					++m_index, type);
			}
			m_wasNull = v == null;
			if ( m_wasNull  ||  type.isInstance(v) )
				return type.cast(v);
			throw new SQLException("Cannot convert " + v.getClass().getName() +
				" to " + type.getName());
		}
		throw new SQLException("Tuple has no more columns");
	}

	// ************************************************************
	// Implementation methods.
	// ************************************************************

	private Number readNumber(Class numberClass) throws SQLException
	{
		return SPIConnection.basicNumericCoersion(
			numberClass, this.readObject());
	}

	private Object readValue(Class valueClass) throws SQLException
	{
		return SPIConnection.basicCoersion(valueClass, this.readObject());
	}

	protected native void _free(long pointer);

	/**
	 * Underlying method that returns the value of the next attribute.
	 *<p>
	 * The signature does not constrain this to return an object of the
	 * requested class, so it can still be used as before by methods that may do
	 * additional coercions. When called by {@link #getObject(Class)}, that
	 * caller enforces the class of the result.
	 */
	private static native Object _getObject(
		long pointer, long tupleDescPointer, int index, Class<?> type)
	throws SQLException;
}
