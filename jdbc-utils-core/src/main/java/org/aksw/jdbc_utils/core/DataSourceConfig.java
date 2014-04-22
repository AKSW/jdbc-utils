package org.aksw.jdbc_utils.core;


public interface DataSourceConfig {
	String getDriverClassName();
	String getJdbcUrl();
	String getUsername();
	String getPassword();
}

