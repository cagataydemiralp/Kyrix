#!/bin/sh

echo "*** setting up postgres roles/databases..."
psql postgresql://postgres:$POSTGRES_PASSWORD@db/postgres -c "CREATE USER kyrix WITH SUPERUSER PASSWORD 'kyrix_password';" || true
psql postgresql://postgres:$POSTGRES_PASSWORD@db/postgres -c "CREATE DATABASE kyrix OWNER kyrix;" || true
psql postgresql://postgres:$POSTGRES_PASSWORD@db/postgres -c"CREATE DATABASE nba OWNER kyrix;" || true

# TODO: throwing errors: CREATE EXTENSION postgis_sfcgal; CREATE EXTENSION address_standardizer;CREATE EXTENSION address_standardizer_data_us;
psql postgresql://kyrix:kyrix_password@db/kyrix -c "CREATE EXTENSION postgis;CREATE EXTENSION postgis_topology;CREATE EXTENSION fuzzystrmatch;CREATE EXTENSION postgis_tiger_geocoder;" || true
psql postgresql://kyrix:kyrix_password@db/nba -c "CREATE EXTENSION postgis;CREATE EXTENSION postgis_topology;CREATE EXTENSION fuzzystrmatch;CREATE EXTENSION postgis_tiger_geocoder;" || true

# workaround this issue: https://github.com/tracyhenry/Kyrix/issues/42
#psql postgresql://kyrix:kyrix_password@db/kyrix -c "CREATE TABLE IF NOT EXISTS project (name VARCHAR(255), content TEXT, dirty int, CONSTRAINT PK_project PRIMARY KEY (name));"
#psql postgresql://kyrix:kyrix_password@db/nba -c "CREATE TABLE IF NOT EXISTS project (name VARCHAR(255), content TEXT, dirty int, CONSTRAINT PK_project PRIMARY KEY (name));"

plays=$(psql postgresql://kyrix:kyrix_password@db/nba -X -P t -P format=unaligned -c "select count(*)>500000 from plays;" || true)
if [ "$plays" = "t" ]; then
    # if you want to force a reload, the easiest way is to dropdb, e.g.
    #   docker exec -u postgres -it kyrix_db_1 sh -c "dropdb nba"
    # note: requires the database to be running, i.e. let docker-compose finish starting up.
    echo "NBA data found - skipping reload to avoid duplicate records."
else
    # TODO: prints ugly error message the first time
    echo "NBA data not found - loading..."
    cat nba_db_psql.sql | grep -v idle_in_transaction_session_timeout | psql postgresql://kyrix:kyrix_password@db/nba | egrep -i 'error' || true
    numplays=$(psql postgresql://kyrix:kyrix_password@db/nba -X -P t -P format=unaligned -c "select count(*) from plays;" || true)
    echo "numplays loaded: $numplays"
fi

echo "*** starting tile server..."
cd /kyrix/tile-server
mvn -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn exec:java -Dexec.mainClass="main.Main" | stdbuf -oL grep -v Downloading: | tee mvn-exec.out &
touch mvn-exec.out
while [ -z "$(egrep 'Done precomputing|Tile server started' mvn-exec.out)" ];do echo "waiting for tile server";sleep 5;done

echo "*** (re)configuring for NBA examples to ensure tile server recomputes..."
cd /kyrix/compiler/examples/nba
node nba.js | egrep -i "error|connected" || true

echo "*** done! Kyrix ready at: http://<host>:8000/  (may need a minute to recompute indexes - watch this log for messages)"

# keep mvn exec running in background, don't exit container
tail -f /dev/null
