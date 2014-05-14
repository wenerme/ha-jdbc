/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (C) 2012  Paul Ferraro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.hajdbc.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.DatabaseClusterConfigurationFactory;
import net.sf.hajdbc.DatabaseClusterFactory;
import net.sf.hajdbc.ExceptionType;
import net.sf.hajdbc.invocation.InvocationStrategies;
import net.sf.hajdbc.invocation.Invoker;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.messages.Messages;
import net.sf.hajdbc.messages.MessagesFactory;
import net.sf.hajdbc.util.TimePeriod;
import net.sf.hajdbc.util.concurrent.MapRegistryStoreFactory;
import net.sf.hajdbc.util.concurrent.LifecycleRegistry;
import net.sf.hajdbc.util.concurrent.Registry;
import net.sf.hajdbc.xml.XMLDatabaseClusterConfigurationFactory;

/**
 * @author  Paul Ferraro
 */
public class Driver extends AbstractDriver
{
	private static final Pattern URL_PATTERN = Pattern.compile("jdbc:ha-jdbc:(?://)?([^/]+)(?:/.+)?");
	private static final String CONFIG = "config";

	private static final Messages messages = MessagesFactory.getMessages();
	static final Logger logger = LoggerFactory.getLogger(Driver.class);

	static volatile TimePeriod timeout = new TimePeriod(10, TimeUnit.SECONDS);
	static volatile DatabaseClusterFactory<java.sql.Driver, DriverDatabase> factory = new DatabaseClusterFactoryImpl<>();
	
	static final Map<String, DatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase>> configurationFactories = new ConcurrentHashMap<>();
	static final ConcurrentMap<String, DriverDatabaseClusterConfigurationBuilder> builders = new ConcurrentHashMap<>();
	private static final Registry.Factory<String, DatabaseCluster<java.sql.Driver, DriverDatabase>, Properties, SQLException> registryFactory = new Registry.Factory<String, DatabaseCluster<java.sql.Driver, DriverDatabase>, Properties, SQLException>()
	{
		@Override
		public DatabaseCluster<java.sql.Driver, DriverDatabase> create(String id, Properties properties) throws SQLException
		{
			DatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase> configurationFactory = configurationFactories.get(id);
			
			if (configurationFactory == null)
			{
				String config = (properties != null) ? properties.getProperty(CONFIG) : null;
				configurationFactory = new XMLDatabaseClusterConfigurationFactory<>(id, config);
			}
			
			return factory.createDatabaseCluster(id, configurationFactory, getConfigurationBuilder(id));
		}

		@Override
		public TimePeriod getTimeout()
		{
			return timeout;
		}
	};
	private static final Registry<String, DatabaseCluster<java.sql.Driver, DriverDatabase>, Properties, SQLException> registry = new LifecycleRegistry<>(registryFactory, new MapRegistryStoreFactory<String>(), ExceptionType.SQL.<SQLException>getExceptionFactory());

	static
	{
		try
		{
			Driver driver = new Driver()
			{
				@Override
				protected void finalize()
				{
					// When the driver instance that was registered with the DriverManager is finalized, close any clusters
					for (String id: builders.keySet())
					{
						try
						{
							close(id);
						}
						catch (Throwable e)
						{
							e.printStackTrace(DriverManager.getLogWriter());
						}
					}
				}
			};
			DriverManager.registerDriver(driver);
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, messages.registerDriverFailed(Driver.class), e);
		}
	}
	
	@Deprecated
	public static void stop(String id) throws SQLException
	{
		close(id);
	}
	
	public static void close(String id) throws SQLException
	{
		registry.remove(id);
	}

	public static DriverDatabaseClusterConfigurationBuilder getConfigurationBuilder(String id)
	{
		DriverDatabaseClusterConfigurationBuilder builder = new DriverDatabaseClusterConfigurationBuilder();
		DriverDatabaseClusterConfigurationBuilder existing = builders.putIfAbsent(id, builder);
		return (existing != null) ? existing : builder;
	}

	public static void setFactory(DatabaseClusterFactory<java.sql.Driver, DriverDatabase> factory)
	{
		Driver.factory = factory;
	}

	public static void setConfigurationFactory(String id, DatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase> configurationFactory)
	{
		configurationFactories.put(id,  configurationFactory);
	}
	
	public static void setTimeout(long value, TimeUnit unit)
	{
		timeout = new TimePeriod(value, unit);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.sql.AbstractDriver#getUrlPattern()
	 */
	@Override
	protected Pattern getUrlPattern()
	{
		return URL_PATTERN;
	}

	/**
	 * {@inheritDoc}
	 * @see java.sql.Driver#connect(java.lang.String, java.util.Properties)
	 */
	@Override
	public Connection connect(String url, final Properties properties) throws SQLException
	{
		String id = this.parse(url);
		
		// JDBC spec compliance
		if (id == null) return null;
		
		DatabaseCluster<java.sql.Driver, DriverDatabase> cluster = registry.get(id, properties);
		DriverProxyFactory driverFactory = new DriverProxyFactory(cluster);
		java.sql.Driver driver = driverFactory.createProxy();
		TransactionContext<java.sql.Driver, DriverDatabase> context = new LocalTransactionContext<>(cluster);

		DriverInvoker<Connection> invoker = new DriverInvoker<Connection>()
		{
			@Override
			public Connection invoke(DriverDatabase database, java.sql.Driver driver) throws SQLException
			{
				return driver.connect(database.getLocation(), properties);
			}
		};
		
		ConnectionProxyFactoryFactory<java.sql.Driver, DriverDatabase, java.sql.Driver> factory = new ConnectionProxyFactoryFactory<>(context);
		return factory.createProxyFactory(driver, driverFactory, invoker, InvocationStrategies.INVOKE_ON_ALL.invoke(driverFactory, invoker)).createProxy();
	}
	
	/**
	 * {@inheritDoc}
	 * @see Driver#getPropertyInfo(java.lang.String, java.util.Properties)
	 */
	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, final Properties properties) throws SQLException
	{
		String id = this.parse(url);
		
		// JDBC spec compliance
		if (id == null) return null;
		
		DatabaseCluster<java.sql.Driver, DriverDatabase> cluster = registry.get(id, properties);
		DriverProxyFactory map = new DriverProxyFactory(cluster);
		
		DriverInvoker<DriverPropertyInfo[]> invoker = new DriverInvoker<DriverPropertyInfo[]>()
		{
			@Override
			public DriverPropertyInfo[] invoke(DriverDatabase database, java.sql.Driver driver) throws SQLException
			{
				return driver.getPropertyInfo(database.getLocation(), properties);
			}
		};
		
		SortedMap<DriverDatabase, DriverPropertyInfo[]> results = InvocationStrategies.INVOKE_ON_ANY.invoke(map, invoker);
		return results.get(results.firstKey());
	}

	/**
	 * @see java.sql.Driver#getParentLogger()
	 */
	@Override
	public java.util.logging.Logger getParentLogger()
	{
		return java.util.logging.Logger.getGlobal();
	}

	private interface DriverInvoker<R> extends Invoker<java.sql.Driver, DriverDatabase, java.sql.Driver, R, SQLException>
	{
	}
}
