package skytrack.demo.controller;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import skytrack.demo.model.Greeting;
import org.springframework.web.client.RestTemplate;


@RestController
public class GreetController {

    private final AtomicLong counter = new AtomicLong();
    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/greeting")
    public Greeting greeting(@RequestParam(defaultValue = "hello") String greet) {
		  return new Greeting(counter.incrementAndGet(), greet);
	  }

    @GetMapping("/data")
    public String fetchData() {
      String url = "https://api.open-meteo.com/v1/forecast?latitude=29.76&longitude=-95.37&current_weather=true";
      return restTemplate.getForObject(url, String.class);
    }

}

