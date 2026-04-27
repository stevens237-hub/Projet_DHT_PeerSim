VER=1.0.4

ifeq ($(OS),Windows_NT)
	SEP := ;
	CLEAN_CMD := del /s /q *.class
else
	SEP := :
	CLEAN_CMD := rm -f `find -name "*.class"`
endif

CP = "src$(SEP)jep-2.3.0.jar$(SEP)djep-1.0.0.jar$(SEP)peersim-1.0.5.jar"

.PHONY: all run compile clean doc release

all: compile run

compile:
	javac -classpath $(CP) src/dht/*.java

run:
	java -classpath $(CP) peersim.Simulator dht_config.cfg

clean:
	$(CLEAN_CMD)

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
