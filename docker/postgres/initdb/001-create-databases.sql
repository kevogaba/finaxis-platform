-- The application database is created by the postgres image from POSTGRES_DB (see compose.yaml),
-- which must match the database name in spring.datasource.url. Only the additional Keycloak
-- database needs creating here.
CREATE DATABASE keycloak;
