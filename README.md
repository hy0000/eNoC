# Evaluation of NoC

This repository contains a configurable noc design implementations. 
In addition, a web evaluation platform is provided which
supports real-time observation of noc performance during simulation.

## Evaluation Demo
### Baseline Topology

### random traffic
dest_x = random()

### reverse traffic
dest_x = b-1-src_x

### custom0 traffic
dest_x = random() if src_x==0 \
dest_x = 0 if src_x!=0