from confluent_kafka import Producer
from time import time, sleep
from random import choice


p = Producer({'bootstrap.servers': 'localhost:9092'})
topic="wan_op_db"
interval = 1

def delivery_report(err, msg):
    """ Called once for each message produced to indicate delivery result.
        Triggered by poll() or flush(). """
    if err is not None:
        print('Message delivery failed: {}'.format(err))
    else:
        pass


def datagen():
    template = """
{
  "seq": %d,
  "links": [
    {
      "key": "Newtec_1",
      "dscp_value": %s
    },
    {
      "key": "Newtec_2",
      "dscp_value": %s
    }
  ],
  "meta_data": {
    "flag": 0,
    "datetime": %d
  }
}
"""
    dscps = [11, 12, 21, 22]
    seq = 1

    while True:
        dscp1 = [choice(dscps),choice(dscps),choice(dscps),choice(dscps)]
        dscp2 = [choice(dscps),choice(dscps),choice(dscps),choice(dscps)]
        datetime = int(time())
        yield template % (seq, str(dscp1), str(dscp2), datetime)
        seq += 1
        sleep(interval)




if __name__ == '__main__':
    for data in datagen():
        p.poll(0)
        p.produce(topic, data.encode('utf-8'), callback=delivery_report, key="wandb")
