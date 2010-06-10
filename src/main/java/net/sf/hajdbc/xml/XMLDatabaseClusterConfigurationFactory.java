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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.SQLException;
import java.text.MessageFormat;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseClusterConfiguration;
import net.sf.hajdbc.DatabaseClusterConfigurationFactory;
import net.sf.hajdbc.DatabaseClusterFactory;
import net.sf.hajdbc.Messages;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;

import org.xml.sax.SAXException;

/**
 * {@link DatabaseClusterConfigurationFactory} that parses an xml configuration file.
 * @author Paul Ferraro
 */
public class XMLDatabaseClusterConfigurationFactory<Z, D extends Database<Z>> implements DatabaseClusterConfigurationFactory<Z, D>
{
	private static final long serialVersionUID = -8796872297122349961L;
	
	private static final String CONFIG_FORMAT_PROPERTY = "ha-jdbc.{0}.configuration"; //$NON-NLS-1$
	private static final String CONFIG_PROPERTY = "ha-jdbc.configuration"; //$NON-NLS-1$
	private static final String DEFAULT_RESOURCE = "ha-jdbc-{0}.xml"; //$NON-NLS-1$
	
	private static final URL SCHEMA = findClassLoaderResource("ha-jdbc.xsd");

	private static final Logger logger = LoggerFactory.getLogger(XMLDatabaseClusterConfigurationFactory.class);
	
	private final Class<? extends DatabaseClusterConfiguration<Z, D>> targetClass;
	private final XMLStreamFactory streamFactory;
	
	private static String identifyResource(String id)
	{
		String resource = System.getProperty(MessageFormat.format(CONFIG_FORMAT_PROPERTY, id));
		
		return (resource != null) ? resource : MessageFormat.format(System.getProperty(CONFIG_PROPERTY, DEFAULT_RESOURCE), id);
	}
	
	/**
	 * Algorithm for searching class loaders for HA-JDBC url.
	 * @param resource a resource name
	 * @return a URL for the HA-JDBC configuration resource
	 */
	private static URL findResource(String resource)
	{
		try
		{
			return new URL(resource);
		}
		catch (MalformedURLException e)
		{
			return findClassLoaderResource(resource);
		}
	}

	private static URL findClassLoaderResource(String resource)
	{
		URL url = Thread.currentThread().getContextClassLoader().getResource(resource);
		
		if (url == null)
		{
			url = DatabaseClusterFactory.class.getClassLoader().getResource(resource);
		}

		if (url == null)
		{
			url = ClassLoader.getSystemResource(resource);
		}
		
		if (url == null)
		{
			throw new IllegalArgumentException(Messages.CONFIG_NOT_FOUND.getMessage(resource));
		}
		
		return url;
	}
	
	public XMLDatabaseClusterConfigurationFactory(Class<? extends DatabaseClusterConfiguration<Z, D>> targetClass, String id, String resource)
	{
		this(targetClass, findResource((resource == null) ? identifyResource(id) : MessageFormat.format(resource, id)));
	}
	
	public XMLDatabaseClusterConfigurationFactory(Class<? extends DatabaseClusterConfiguration<Z, D>> targetClass, URL url)
	{
		this(targetClass, url.getProtocol().equals("file") ? new FileXMLStreamFactory(new File(url.getPath())) : new URLXMLStreamFactory(url));
		
		logger.log(Level.INFO, "Using url {0}", url);
	}
	
	public XMLDatabaseClusterConfigurationFactory(Class<? extends DatabaseClusterConfiguration<Z, D>> targetClass, XMLStreamFactory streamer)
	{
		this.targetClass = targetClass;
		this.streamFactory = streamer;
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterConfigurationFactory#createConfiguration(java.lang.String, java.lang.Class)
	 */
	@Override
	public DatabaseClusterConfiguration<Z, D> createConfiguration() throws SQLException
	{
		try
		{
			SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			Schema schema = schemaFactory.newSchema(SCHEMA);
			
			Unmarshaller unmarshaller = JAXBContext.newInstance(this.targetClass).createUnmarshaller();
			unmarshaller.setSchema(schema);
			
			return this.targetClass.cast(unmarshaller.unmarshal(this.streamFactory.createSource()));
		}
		catch (JAXBException e)
		{
			throw new SQLException(e);
		}
		catch (SAXException e)
		{
			throw new SQLException(e);
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterConfigurationListener#added(net.sf.hajdbc.Database, net.sf.hajdbc.DatabaseClusterConfiguration)
	 */
	@Override
	public void added(D database, DatabaseClusterConfiguration<Z, D> configuration)
	{
		this.export(configuration);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterConfigurationListener#removed(net.sf.hajdbc.Database, net.sf.hajdbc.DatabaseClusterConfiguration)
	 */
	@Override
	public void removed(D database, DatabaseClusterConfiguration<Z, D> configuration)
	{
		this.export(configuration);
	}
	
	private void export(DatabaseClusterConfiguration<Z, D> configuration)
	{
		try
		{
			Marshaller marshaller = JAXBContext.newInstance(configuration.getClass()).createMarshaller();
			
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);				
			marshaller.marshal(configuration, this.streamFactory.createResult());
		}
		catch (JAXBException e)
		{
			logger.log(Level.WARN, e, Messages.CONFIG_STORE_FAILED.getMessage(), this.streamFactory);
		}
	}
}
