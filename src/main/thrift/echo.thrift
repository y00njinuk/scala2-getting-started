namespace java com.example.echo.thrift
namespace scala com.example.echo.thrift

service Echo {
  string ping(1: string msg)
}
