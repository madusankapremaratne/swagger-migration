Bundle to migrate tenants from API-M 1.6 to 1.8.

Follow the steps below to learn how to use the tool.
1. Copy the bundle to AM 1.8 and start the server with -Dmigrate=1.6 to run the 1.6 to 1.7 swagger and doc resourcce migrations.
2. Copy the bundle to AM 1.8 and start the server with -Dmigrate=1.7 to run the 1.7 to 1.8 swagger resource migration.

Notes
- The mysql.sql in the migrate directories should be run against the API_M database.
- Set apim.home in build.xml of ./migration-1.6.0_to_1.7.0/api-migration/ (check other build.xml files as well)
- To run the rxt migration script which I shared you need to install the library xmlstarlet. Make sure to mention this in the doc as well. 

-----------migration for AM 1.9-------
Run with -Dmigrate=1.8
