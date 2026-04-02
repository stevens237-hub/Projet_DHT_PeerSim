package helloWorld;

import peersim.config.*;
import peersim.core.*;
import peersim.edsim.*;

public class HWTransport implements Protocol {

    //variables pour calculer la latence entre les noeuds
    private final long min;
    
    private final long range;
    
    
    public HWTransport(String prefix) {
	System.out.println("Transport Layer Enabled");
	//recuperation des valeurs extremes de latence depuis le fichier de configuration
	min = Configuration.getInt(prefix + ".mindelay");
	long max = Configuration.getInt(prefix + ".maxdelay");
	if (max < min) {
	    System.out.println("The maximum latency cannot be smaller than the minimum latency");
	    System.exit(1);
	}
	range = max-min+1;
    }
    
    
    public Object clone() {
	return this;
    }
    

    //envoi d'un message: il suffit de l'ajouter a la file d'evenements
    public void send(Node src, Node dest, Object msg, int pid) {
	long delay = getLatency(src,dest);
	EDSimulator.add(delay, msg, dest, pid);
    }
    
    
    //latence random entre la borne min et la borne max
    public long getLatency(Node src, Node dest) {
	return (range==1?min:min + CommonState.r.nextLong(range));
    }


}

