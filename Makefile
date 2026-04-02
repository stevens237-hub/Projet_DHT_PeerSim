VER=1.0.4

.PHONY: all run compile clean doc release

all: compile run

compile:
	javac -classpath "src;jep-2.3.0.jar;djep-1.0.0.jar" src/dht/*.java

run:
	java -classpath "src;jep-2.3.0.jar;djep-1.0.0.jar" peersim.Simulator dht_config.cfg

clean:
	rm -f `find -name "*.class"`

doc:
	rm -rf doc/*
	javadoc -overview overview.html -classpath src:jep-2.3.0.jar:djep-1.0.0.jar -d doc \
                -group "Peersim" "peersim*" \
                -group "DHT" "dht.*" \
		peersim \
		peersim.cdsim \
		peersim.config \
		peersim.core \
		peersim.dynamics \
		peersim.edsim \
		peersim.graph \
		peersim.rangesim \
		peersim.reports \
		peersim.transport \
		peersim.util \
		peersim.vector \
		dht
