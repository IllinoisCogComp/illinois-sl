version=0.2

mvn compile assembly:single
mvn package
mvn source:jar
mvn cite
cp target/*.jar dist
yes| cp -rf target/site/apidocs webpage
mvn clean

zip -r illinois-SL.$version.zip src target webpage/tutorial.html scripts/*.sh  data dist config test pom.xml README.standalone webpage/apidocs