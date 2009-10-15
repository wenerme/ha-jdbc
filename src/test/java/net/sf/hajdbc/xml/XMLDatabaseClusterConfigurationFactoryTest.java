/*
 * HA-JDBC: High-Availability JDBC
 * Copyright 2004-2009 Paul Ferraro
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
package net.sf.hajdbc.xml;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Map;

import junit.framework.Assert;
import net.sf.hajdbc.SynchronizationStrategy;
import net.sf.hajdbc.balancer.BalancerFactoryEnum;
import net.sf.hajdbc.cache.DatabaseMetaDataCacheFactoryEnum;
import net.sf.hajdbc.dialect.DialectFactoryEnum;
import net.sf.hajdbc.durability.DurabilityFactoryEnum;
import net.sf.hajdbc.sql.DefaultExecutorServiceProvider;
import net.sf.hajdbc.sql.DriverDatabase;
import net.sf.hajdbc.sql.DriverDatabaseClusterConfiguration;
import net.sf.hajdbc.sql.TransactionMode;

import org.easymock.EasyMock;
import org.junit.Test;

public class XMLDatabaseClusterConfigurationFactoryTest
{
	@Test
	public void createConfiguration() throws SQLException, IOException
	{
		StringBuilder builder = new StringBuilder();
		builder.append("<ha-jdbc>");
		builder.append("\t<sync id=\"passive\" class=\"net.sf.hajdbc.sync.PassiveSynchronizationStrategy\"></sync>");
		builder.append("\t<cluster default-sync=\"passive\">");
		builder.append("\t\t<database id=\"db1\">");
		builder.append("\t\t\t<name>jdbc:mock:db1</name>");
		builder.append("\t\t</database>");
		builder.append("\t\t<database id=\"db2\">");
		builder.append("\t\t\t<name>jdbc:mock:db2</name>");
		builder.append("\t\t</database>");
		builder.append("\t</cluster>");
		builder.append("</ha-jdbc>");
		
		String xml = builder.toString();
		
		CharacterStreamer streamer = EasyMock.createStrictMock(CharacterStreamer.class);
		
		XMLDatabaseClusterConfigurationFactory factory = new XMLDatabaseClusterConfigurationFactory(streamer);
		
		EasyMock.expect(streamer.getReader()).andReturn(new StringReader(xml));
		
		EasyMock.replay(streamer);
		
		DriverDatabaseClusterConfiguration configuration = factory.createConfiguration(DriverDatabaseClusterConfiguration.class);
		
		EasyMock.verify(streamer);
		
		Assert.assertNull(configuration.getDispatcherFactory());
		Map<String, SynchronizationStrategy> strategies = configuration.getSynchronizationStrategyMap();
		Assert.assertNotNull(strategies);
		Assert.assertEquals(1, strategies.size());
		
		SynchronizationStrategy strategy = strategies.get("passive");
		
		Assert.assertNotNull(strategy);
		
		Assert.assertSame(BalancerFactoryEnum.ROUND_ROBIN, configuration.getBalancerFactory());
	   Assert.assertSame(DatabaseMetaDataCacheFactoryEnum.EAGER, configuration.getDatabaseMetaDataCacheFactory());
	   Assert.assertEquals("passive", configuration.getDefaultSynchronizationStrategy());
	   Assert.assertSame(DialectFactoryEnum.STANDARD, configuration.getDialectFactory());
	   Assert.assertSame(DurabilityFactoryEnum.FINE, configuration.getDurabilityFactory());
	   Assert.assertSame(TransactionMode.SERIAL, configuration.getTransactionMode());
	   
	   DefaultExecutorServiceProvider executorProvider = (DefaultExecutorServiceProvider) configuration.getExecutorProvider();
	   
	   Assert.assertNotNull(executorProvider);
	   Assert.assertEquals(60, executorProvider.getMaxIdle());
	   Assert.assertEquals(100, executorProvider.getMaxThreads());
	   Assert.assertEquals(0, executorProvider.getMinThreads());
	   
	   Assert.assertNull(configuration.getAutoActivationExpression());
	   Assert.assertNull(configuration.getFailureDetectionExpression());
	   
	   Assert.assertFalse(configuration.isCurrentDateEvaluationEnabled());
	   Assert.assertFalse(configuration.isCurrentTimeEvaluationEnabled());
	   Assert.assertFalse(configuration.isCurrentTimestampEvaluationEnabled());
	   Assert.assertFalse(configuration.isIdentityColumnDetectionEnabled());
	   Assert.assertFalse(configuration.isRandEvaluationEnabled());
	   Assert.assertFalse(configuration.isSequenceDetectionEnabled());
	   
	   Map<String, DriverDatabase> databases = configuration.getDatabaseMap();
	   
	   Assert.assertNotNull(databases);
	   Assert.assertEquals(2, databases.size());
	   
	   DriverDatabase db1 = databases.get("db1");
	   
	   Assert.assertNotNull(db1);
	   Assert.assertEquals("db1", db1.getId());
	   Assert.assertEquals("jdbc:mock:db1", db1.getName());
	   Assert.assertEquals(1, db1.getWeight());
	   Assert.assertFalse(db1.isLocal());
	   Assert.assertFalse(db1.isActive());
	   Assert.assertFalse(db1.isDirty());
	   
	   DriverDatabase db2 = databases.get("db2");
	   
	   Assert.assertNotNull(db2);
	   Assert.assertEquals("db2", db2.getId());
	   Assert.assertEquals("jdbc:mock:db2", db2.getName());
	   Assert.assertEquals(1, db2.getWeight());
	   Assert.assertFalse(db2.isLocal());
	   Assert.assertFalse(db2.isActive());
	   Assert.assertFalse(db2.isDirty());
	   
	   EasyMock.reset(streamer);
	   
	   StringWriter writer = new StringWriter();
	   
	   EasyMock.expect(streamer.getWriter()).andReturn(writer);
	   
	   EasyMock.replay(streamer);
	   
		factory.added(null, configuration);
		
		EasyMock.verify(streamer);
		
		System.out.println(writer.toString());
	}
}
