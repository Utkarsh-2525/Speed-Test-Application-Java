# SpeedTest

A simple Java Spring Boot application to measure internet speed (download, upload) and ping using the [JSpeedTest](https://github.com/bmartel/jspeedtest) library and custom ping measurement.

## Features

- Measures download speed (Mbps)
- Measures upload speed (Mbps)
- Measures ping (ms) using ICMP echo requests
- Spring Boot backend with a clean service structure
- Easy to extend and integrate with a web GUI (e.g., Thymeleaf)

## Prerequisites

- Java 21 or later
- Maven 3.x
- Internet connection for speed tests

## Setup and Installation

1. Clone this repository:

   ```bash
   git clone https://github.com/yourusername/SpeedTest.git
   cd SpeedTest
   ```
2. Build the Project with Maven
   ```bash
   mvn clean install
   ```
3. Run the Spring Boot Application
   ```bash
   mvn spring-boot:run
